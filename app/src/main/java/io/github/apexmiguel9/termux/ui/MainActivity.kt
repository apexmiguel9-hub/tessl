package io.github.apexmiguel9.termux.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.apexmiguel9.termux.bootstrap.AppPaths
import io.github.apexmiguel9.termux.runtime.BionicRuntime
import io.github.apexmiguel9.termux.runtime.ProotDistroRuntime
import io.github.apexmiguel9.termux.runtime.Runtime
import io.github.apexmiguel9.termux.session.ClipboardBridge
import io.github.apexmiguel9.termux.session.TerminalSession

class MainActivity : ComponentActivity() {

    private val sessions = mutableStateListOf<TerminalSession>()
    private var activeId by mutableStateOf<Long?>(null)
    private var showShell by mutableStateOf(false)

    private lateinit var paths: AppPaths
    private val runtimes = mutableMapOf<String, Runtime>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        paths = AppPaths.get(this).also { it.ensureDirs() }
        runtimes["bionic"] = BionicRuntime(paths)
        runtimes["proot:debian"] = ProotDistroRuntime(paths, "debian")

        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        ClipboardBridge.copy = { text ->
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("tessl", text))
        }
        ClipboardBridge.paste = {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }

        setContent {
            if (!showShell) {
                BootstrapGate(
                    paths = paths,
                    archiveUrl = BOOTSTRAP_URL,
                    onReady = { showShell = true },
                )
                return@setContent
            }
            Shell(
                sessions = sessions,
                activeId = activeId,
                onSelect = { activeId = it },
                onClose = ::closeSession,
                onNew = { newSession(defaultRuntime()) },
                onKey = { s, code, meta -> s.onKey(code, meta) },
            )
        }
    }

    /**
     * Default to the proot distro when it is installed, since that is where
     * the useful software lives. Falls back to the bionic prefix, which is
     * always present once the bootstrap has been unpacked.
     */
    private fun defaultRuntime(): Runtime =
        runtimes["proot:debian"]?.takeIf { it.isReady } ?: runtimes.getValue("bionic")

    private fun newSession(runtime: Runtime) {
        val s = TerminalSession(
            id = TerminalSession.newId(),
            runtime = runtime,
            paths = paths,
            onInvalidate = { /* snapshot state bump inside the session */ },
            onTitle = { },
            onBell = { },
        )
        sessions += s
        activeId = s.id
        // Spawn off the main thread: fork+exec plus a cold bash is not instant.
        Thread({ runCatching { s.start() } }, "tessl-session-start").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeSession(id: Long) {
        sessions.firstOrNull { it.id == id }?.let {
            it.destroy()
            sessions.remove(it)
        }
        if (activeId == id) activeId = sessions.lastOrNull()?.id
    }

    companion object {
        /**
         * Stock Termux bootstrap. Relocator rewrites the prefix at install time,
         * so this works under our own applicationId.
         * Swap for our own fork's release once termux-packages CI has produced
         * a zip with COMPILED_PREFIX baked in.
         */
        const val BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/" +
                "bootstrap-2026.09.27-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    }

    override fun onDestroy() {
        sessions.forEach { it.destroy() }
        sessions.clear()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val active = sessions.firstOrNull { it.id == activeId } ?: return super.onKeyDown(keyCode, event)
        return active.onKey(keyCode, event.metaState)
    }
}

@Composable
private fun Shell(
    sessions: androidx.compose.runtime.snapshots.SnapshotStateList<TerminalSession>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onKey: (TerminalSession, Int, Int) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).imePadding()) {
        TerminalShell(
            sessions = sessions,
            activeId = activeId,
            onSelect = onSelect,
            onClose = onClose,
            onNew = onNew,
            onKey = onKey,
        )
    }
}
