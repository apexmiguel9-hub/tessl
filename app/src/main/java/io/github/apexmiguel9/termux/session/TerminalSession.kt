package io.github.apexmiguel9.termux.session

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalEmulatorClient
import com.termux.terminal.TerminalOutput
import io.github.apexmiguel9.termux.bootstrap.AppPaths
import io.github.apexmiguel9.termux.pty.PtyProcess
import io.github.apexmiguel9.termux.runtime.Runtime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger

/**
 * One shell: a [PtyProcess] feeding a [TerminalEmulator].
 *
 * The emulator does all the hard VT work (escape sequences, UTF-8, wcwidth,
 * scrollback, sixel, alt-screen). We only own the edges: read bytes, append
 * them, redraw.
 *
 * Threading: [ptyOutput] runs on the pty reader thread, the emulator is not
 * thread-safe, so every mutation is funnelled through [lock]. The renderer
 * reads under the same lock when it snapshots, which keeps tearing impossible
 * without paying for a full copy of the buffer per frame.
 */
class TerminalSession(
    val id: Long,
    val runtime: Runtime,
    private val paths: AppPaths,
    private val onInvalidate: () -> Unit,
    private val onTitle: (String) -> Unit,
    private val onBell: () -> Unit,
) {

    private val lock = Any()

    private var pty: PtyProcess? = null

    /**
     * Monotonic counter bumped on every pty write and every key press.
     *
     * Snapshot state, so it may be written from the pty reader thread. The
     * renderer reads it inside its draw scope, which is what invalidates the
     * canvas; passing a plain counter around by hand is what makes Compose
     * terminals redraw on every frame by accident.
     */
    var revision by mutableIntStateOf(0)
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    var title: String = ""
        private set

    var rows = 24
        private set
    var cols = 80
        private set

    private var cellWidth = 9
    private var cellHeight = 18

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            // TerminalEmulator.append(buffer, length) has no offset overload,
            // so slice first. This path is only used by paste/title plumbing;
            // pty output goes through the append(bytes, size) call below.
            val slice = if (offset == 0 && count == data.size) data
                else data.copyOfRange(offset, offset + count)
            synchronized(lock) {
                emulator.append(slice, slice.size)
            }
            revision++
            onInvalidate()
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            title = newTitle.orEmpty()
            onTitle(title)
        }

        override fun onCopyTextToClipboard(text: String?) {
            text?.let { ClipboardBridge.copy?.invoke(it) }
        }

        override fun onPasteTextFromClipboard() {
            ClipboardBridge.paste?.invoke()?.let { writeBytes(it.toByteArray(Charsets.UTF_8)) }
        }

        override fun onBell() = onBell.invoke()

        override fun onColorsChanged() = onInvalidate()
    }

    private val client = object : TerminalEmulatorClient {
        override fun onTextChanged() = onInvalidate()

        override fun onTitleChanged(newTitle: String?) {
            title = newTitle.orEmpty()
            onTitle(title)
        }

        override fun onBell() = onBell.invoke()

        override fun onColorsChanged() = onInvalidate()

        override fun onTerminalCursorStateChange(visible: Boolean) = onInvalidate()

        override fun getTerminalCursorStyle(): Int? = null
    }

    /**
     * MUST be declared after [output] and [client].
     *
     * Kotlin runs property initialisers in declaration order, and
     * TerminalEmulator's constructor calls reset(), which immediately does
     * `mClient.onColorsChanged()`. With emulator initialised first, output and
     * client were still null and every new session died with an NPE.
     */
    var emulator: TerminalEmulator = newEmulator(80, 24, 9, 18)
        private set

    private fun newEmulator(c: Int, r: Int, cw: Int, ch: Int) =
        TerminalEmulator(output, c, r, cw, ch, SCROLLBACK_ROWS, client)

    fun start() {
        val home = paths.homeDir
        home.mkdirs()
        val p = runtime.spawn(
            rows = rows,
            cols = cols,
            home = home,
            onOutput = { bytes ->
                synchronized(lock) { emulator.append(bytes, bytes.size) }
                android.util.Log.i(
                    "tessl/session",
                    "pty +${bytes.size}B " + String(bytes, Charsets.UTF_8)
                        .replace('\r', '\\r').replace('\n', '\\n').take(100),
                )
                revision++
                onInvalidate()
            },
            onExit = { code ->
                android.util.Log.i("tessl/session", "exited code=$code")
                exitCode = code
                onInvalidate()
            },
        )
        pty = p
        p.start()
    }

    fun writeBytes(bytes: ByteArray) {
        pty?.write(bytes)
    }

    fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

    /**
     * Feed a key. Returns true if the emulator consumed it.
     * [metaState] is android.view.KeyEvent.getMetaState().
     */
    fun onKey(keyCode: Int, metaState: Int): Boolean {
        val alt = metaState and android.view.KeyEvent.META_ALT_ON != 0
        val ctrl = metaState and android.view.KeyEvent.META_CTRL_ON != 0
        val shift = metaState and android.view.KeyEvent.META_SHIFT_ON != 0

        // Ctrl+letter becomes its control code. The emulator wants the
        // keycode for everything else.
        if (ctrl && !alt && keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
            val ch = ('a'.code + (keyCode - android.view.KeyEvent.KEYCODE_A))
            writeBytes(byteArrayOf(ch.toByte()))
            return true
        }
        if (alt && !ctrl && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            writeBytes("\u001b\r".toByteArray(Charsets.UTF_8))
            return true
        }
        if (alt && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            writeBytes(byteArrayOf(0x7f)) // DEL
            return true
        }
        if (shift && keyCode == android.view.KeyEvent.KEYCODE_TAB) {
            writeBytes(byteArrayOf(0x1b, '['.code.toByte(), 'Z'.code.toByte())) // ESC [ Z
            return true
        }

        synchronized(lock) {
            emulator.processCodePoint(keyCode)
        }
        android.util.Log.i(
            "tessl/session",
            "key=$keyCode meta=$metaState tail=" +
                emulator.getTranscriptText().takeLast(120).replace('\n', '|'),
        )
        revision++
        onInvalidate()
        return true
    }

    /** Paste, going through the emulator so bracketed paste is honoured. */
    fun paste(text: String) {
        synchronized(lock) { emulator.paste(text) }
        onInvalidate()
    }

    fun resize(newRows: Int, newCols: Int, cw: Int, ch: Int) {
        if (newRows == rows && newCols == cols && cw == cellWidth && ch == cellHeight) return
        rows = newRows
        cols = newCols
        cellWidth = cw
        cellHeight = ch
        synchronized(lock) {
            emulator.resize(newCols, newRows, cw, ch)
        }
        pty?.resize(newRows, newCols)
        revision++
        onInvalidate()
    }

    fun selectedText(x1: Int, y1: Int, x2: Int, y2: Int): String =
        synchronized(lock) { emulator.getSelectedText(x1, y1, x2, y2) }

    fun snapshot(): TerminalEmulator = synchronized(lock) { emulator }

    fun destroy() {
        pty?.close()
        pty = null
    }

    companion object {
        const val SCROLLBACK_ROWS = 2000
        private val nextId = AtomicInteger(1)
        fun newId(): Long = nextId.getAndIncrement().toLong()
    }
}

/** Set by the Activity so the session layer stays free of Android UI deps. */
object ClipboardBridge {
    @Volatile
    var copy: ((String) -> Unit)? = null

    @Volatile
    var paste: (() -> String?)? = null
}
