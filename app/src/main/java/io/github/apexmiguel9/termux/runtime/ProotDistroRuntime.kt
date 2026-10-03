package io.github.apexmiguel9.termux.runtime

import io.github.apexmiguel9.termux.bootstrap.AppPaths
import io.github.apexmiguel9.termux.pty.PtyProcess
import java.io.File

/**
 * Layer 2: a glibc distro rootfs reached through proot.
 *
 * Why this is the main environment and not the exception:
 *   - The bionic prefix only has what we chose to compile (109 packages).
 *     Everything else -- a real apt with real repos, python, gcc, git -- is
 *     glibc-shaped and lives here.
 *   - proot needs no root and no kernel support, which is the only thing that
 *     works in an unprivileged Android app sandbox.
 *
 * proot choice matters:
 *   - The ptrace build (`proot`) costs two context switches per intercepted
 *     syscall and maps the guest's whole address space. `apt install` peaks
 *     in the hundreds of MB of RSS.
 *   - The seccomp-bpf build (`proot-me/proot`, shipped as `proot` in the
 *     Termux repo since 2023) needs no ptrace at all. Same binary name, so we
 *     probe for the seccomp build rather than hardcoding a different one.
 *
 * The pty stays on the bionic host. That is not a simplification, it is
 * required: under proot the guest cannot open /dev/kgsl-3d0, /dev/mali0 or
 * /dev/dri, so anything GPU-related has to be proxied by the host process.
 */
class ProotDistroRuntime(
    private val paths: AppPaths,
    private val distroId: String = "debian",
    /** proot binary on the bionic side. */
    private val prootBin: File = paths.prootBin(),
) : Runtime {

    override val id: String get() = "proot:$distroId"
    override val displayName: String get() = distroId.replaceFirstChar { it.uppercase() }

    override val rootDir: File get() = paths.distroDir(distroId)
    override val prefixDir: File get() = File(rootDir, "usr")

    override val isReady: Boolean
        get() = prootBin.canExecute() && File(rootDir, "bin/bash").canExecute()

    override suspend fun prepare(onProgress: (String) -> Unit) {
        onProgress("$distroId rootfs at ${rootDir.absolutePath}")
    }

    override fun environment(home: File): List<String> = buildList {
        // Values the guest sees. Paths are guest paths: inside proot, / is
        // the rootfs, so none of these mention the app's data dir.
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        add("HOME=/root")
        add("TERM=xterm-256color")
        add("COLORTERM=truecolor")
        add("LANG=${if (distroId == "debian") "en_US.UTF-8" else "C.UTF-8"}")
        add("DEBIAN_FRONTEND=noninteractive")
        add("TMPDIR=/tmp")
        // Keep glibc's allocator from claiming per-core arenas inside a
        // memory-capped sandbox.
        add("MALLOC_ARENA_MAX=1")
        add("MALLOC_TRIM_THRESHOLD_=131072")
        add("MALLOC_MMAP_THRESHOLD_=65536")
        add("TESSL_HOST_PREFIX=${paths.prefixDir.absolutePath}")
    }

    /**
     * proot arguments, in order. Each -b is a host path the guest should see.
     * Keeping this list tight is what controls both RAM and correctness:
     * bind-mounting all of /data would expose the whole bionic prefix to the
     * guest and break any guest binary that links against it.
     */
    override fun spawnArgs(): List<String> {
        val a = mutableListOf(
            // seccomp-bpf build: no ptrace, dramatically cheaper
            "--root-id",
            "-r", rootDir.absolutePath,
            // $PREFIX: host side, so the host's own tools can be called in
            "-b", "${paths.prefixDir}:${paths.prefixDir}",
            // /tmp, /dev, /proc as the guest expects them
            "-b", "/dev",
            "-b", "/system",
            "-b", "/apex",
            "-b", "/sdcard",
            // Kernel ABI surface proot cannot emulate itself
            "-b", "/system/bin",
        )
        // Loop device support for squashfs/erofs images, if ever used.
        if (File("/dev/loop-control").exists()) {
            a += listOf("-b", "/dev/loop-control", "-b", "/dev/loop0")
        }
        return a
    }

    override fun shellArgv(login: Boolean): List<String> =
        listOf(prootBin.absolutePath) + spawnArgs() +
            listOf("/bin/bash", if (login) "-l" else "-s")

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
        // Inside proot "/" is the guest rootfs, so cwd is the guest home.
        initialCwd = "/root",
        onOutput = onOutput,
        onExit = onExit,
    )
}
