package io.github.apexmiguel9.termux.pty

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A child process attached to a pseudo-terminal, with a background reader
 * thread that drains the master fd into [onOutput].
 *
 * The reader is a plain Thread rather than a coroutine on purpose: JNI calls
 * need an attached thread, and `nativeRead` is a short poll so a dedicated
 * thread costs nothing and removes all the attach/detach churn from the hot
 * path.
 */
class PtyProcess(
    private val argv: List<String>,
    private val envp: List<String>?,
    initialRows: Int,
    initialCols: Int,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: (Int) -> Unit,
) : Closeable {

    private val handle: Long = PtyNative.nativeSpawn(
        argv.toTypedArray(),
        envp?.toTypedArray(),
        initialRows,
        initialCols,
    )

    private val closed = AtomicBoolean(false)
    private val exitCode = AtomicInteger(-1)

    @Volatile
    private var reader: Thread? = null

    val pid: Int get() = PtyNative.nativePid(handle)

    val isAlive: Boolean get() = !closed.get() && PtyNative.nativeAlive(handle)

    fun start() {
        val t = Thread({ readLoop() }, "tessl-pty-reader-$pid")
        t.isDaemon = true
        t.start()
        reader = t
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                // 250ms keeps shutdown latency low without spinning.
                val chunk = PtyNative.nativeRead(handle, 250)
                if (chunk == null) {
                    // nativeRead returns null for BOTH timeout and EOF, so a
                    // plain `?: continue` turns a finished child into an
                    // infinite poll loop that never reaches nativeWait and
                    // never reports the exit.
                    if (!PtyNative.nativeAlive(handle)) break
                    continue
                }
                if (chunk.isEmpty()) continue
                onOutput(chunk)
            }
        } catch (t: Throwable) {
            android.util.Log.e("tessl/pty", "reader loop died", t)
        }
        val execErrno = try {
            PtyNative.nativeExecErrno(handle)
        } catch (_: Throwable) {
            0
        }
        val code = try {
            PtyNative.nativeWait(handle)
        } catch (t: Throwable) {
            android.util.Log.e("tessl/pty", "wait failed", t)
            -1
        }
        android.util.Log.i(
            "tessl/pty",
            "pid=$pid exit=$code execErrno=$execErrno" +
                if (execErrno != 0) " (${strerrorOf(execErrno)})" else "",
        )
        exitCode.set(code)
        closed.set(true)
        onExit(code)
    }

    fun write(bytes: ByteArray) {
        if (closed.get()) return
        PtyNative.nativeWrite(handle, bytes)
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    fun resize(rows: Int, cols: Int) {
        if (closed.get()) return
        PtyNative.nativeResize(handle, rows, cols)
    }

    /** Non-blocking liveness probe for the UI. */
    fun pollExit(): Int? = if (closed.get()) exitCode.get() else null

    private fun strerrorOf(e: Int): String = runCatching {
        java.io.File("/proc/self/maps").exists()
        "errno $e"
    }.getOrDefault("errno $e")

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            // Already finished; just make sure the fd is gone.
            return
        }
        PtyNative.nativeKill(handle)
        PtyNative.nativeClose(handle)
        reader?.interrupt()
        reader = null
    }
}
