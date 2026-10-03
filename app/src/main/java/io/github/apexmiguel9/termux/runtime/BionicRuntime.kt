package io.github.apexmiguel9.termux.runtime

import io.github.apexmiguel9.termux.bootstrap.AppPaths
import io.github.apexmiguel9.termux.pty.PtyProcess
import java.io.File

/**
 * Layer 1: the bionic bootstrap we compile ourselves.
 *
 * Binaries here were built with
 *   TERMUX_PREFIX=/data/data/io.github.apexmiguel9.termux/files/usr
 * so no path rewriting is needed at install time. This is the minimum viable
 * shell: bash, coreutils, apt, proot. It is intentionally *not* the main
 * environment -- see [ProotDistroRuntime] for that.
 */
class BionicRuntime(private val paths: AppPaths) : Runtime {

    override val id = "bionic"
    override val displayName = "Bionic"

    override val rootDir: File get() = paths.filesDir
    override val prefixDir: File get() = paths.prefixDir

    override val isReady: Boolean
        get() = File(prefixDir, "bin/bash").canExecute()

    override suspend fun prepare(onProgress: (String) -> Unit) {
        // Installation is a separate concern handled by the installer; by the
        // time a session asks for this runtime the tree is already unpacked.
        onProgress("bionic prefix ready")
    }

    override fun environment(home: File): List<String> =
        Runtime.baseEnv(home, prefixDir, listOf(File(prefixDir, "lib")))

    override fun shellArgv(login: Boolean): List<String> =
        listOf(File(prefixDir, "bin/bash").absolutePath, if (login) "-l" else "-s")

    override fun spawn(
        rows: Int,
        cols: Int,
        home: File,
        onOutput: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
    ): PtyProcess = PtyProcess(
        argv = shellArgv(login = true),
        envp = environment(home),
        initialRows = rows,
        initialCols = cols,
        onOutput = onOutput,
        onExit = onExit,
    )
}
