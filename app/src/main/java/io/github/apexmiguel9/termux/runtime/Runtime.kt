package io.github.apexmiguel9.termux.runtime

import io.github.apexmiguel9.termux.pty.PtyProcess
import java.io.File

/**
 * What a shell session needs in order to run.
 *
 * The pty always lives on the bionic host, because Android never gives an app
 * process access to the guest's filesystems or (under proot) to the GPU. The
 * runtime only decides *what argv* to exec on that pty and *what environment*
 * to hand it, which is why swapping bionic-native for a glibc distro does not
 * touch the UI, the emulator, or the pty code.
 */
interface Runtime {

    val id: String
    val displayName: String

    /** Root of the tree this runtime owns. Always inside the app's data dir. */
    val rootDir: File

    /** $PREFIX equivalent. For proot runtimes this is inside the rootfs. */
    val prefixDir: File

    /** True once the tree is installed and spawn() will actually work. */
    val isReady: Boolean

    /** Human-readable progress while preparing, for the install UI. */
    suspend fun prepare(onProgress: (String) -> Unit)

    /** Environment for an interactive login shell. */
    fun environment(home: File): List<String>

    /** argv[0..] to exec on the pty. */
    fun shellArgv(login: Boolean): List<String>

    /** Bind mounts / intercepts the runtime needs, already encoded in shellArgv. */
    fun spawnArgs(): List<String> = emptyList()

    fun spawn(
        rows: Int,
        cols: Int,
        home: File,
        onOutput: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
    ): PtyProcess

    companion object {
        /** Standard env every runtime shares. TERM is what makes ncurses work. */
        fun baseEnv(home: File, prefix: File, ldLibraryPath: List<File>): List<String> {
            val ld = ldLibraryPath.joinToString(":") { it.absolutePath }
            return buildList {
                add("PATH=${prefix}/bin:/system/bin")
                add("HOME=${home.absolutePath}")
                add("PREFIX=${prefix.absolutePath}")
                add("TMPDIR=${prefix}/tmp")
                add("LANG=en_US.UTF-8")
                add("TERM=xterm-256color")
                add("COLORTERM=truecolor")
                add("SHELL=${prefix}/bin/bash")
                if (ld.isNotEmpty()) add("LD_LIBRARY_PATH=$ld")
                add("TERMUX_APP_PACKAGE=io.github.apexmiguel9.termux")
                // termux-tools' `pkg` switches on this and leaves its apt cache
                // directory unset without it.
                add("TERMUX_APP_PACKAGE_MANAGER=apt")
            }
        }
    }
}
