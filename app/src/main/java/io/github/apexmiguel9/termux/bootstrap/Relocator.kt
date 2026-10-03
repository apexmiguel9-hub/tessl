package io.github.apexmiguel9.termux.bootstrap

import java.io.File
import java.io.RandomAccessFile

/**
 * Moves a stock Termux bootstrap to our own package name.
 *
 * WHY THIS EXISTS, with measurements taken against
 * termux-packages release bootstrap-2026.09.27-r1 (aarch64, 3774 files):
 *
 *   - 338 ELF files. 337 of them carry
 *         RUNPATH = /data/data/com.termux/files/usr/lib
 *     and, from Termux's own strings output, roughly 200 further absolute
 *     references in .rodata:
 *         /data/data/com.termux/files/usr/bin/bash    x60
 *         /data/data/com.termux/files/usr/bin/login  x58
 *         /data/data/com.termux/files/home           x57
 *         /data/data/com.termux/files/usr/tmp/       x42
 *         /data/data/com.termux/files/usr/bin/sh     x30
 *
 *   - PT_INTERP is /system/bin/linker64 for all of them, so libc, libdl and
 *     the dynamic linker are the platform's and need no work.
 *
 * RUNPATH is the easy half. `$ORIGIN/../lib` is 14 bytes where the original is
 * 35, so it fits in place inside DT_STRTAB with a NUL terminator, and bionic
 * does expand $ORIGIN in DT_RUNPATH (verified by running a relocated
 * coreutils on Android 11 / API 30). So the library search path is relocatable
 * for any package name, however long.
 *
 * The .rodata half is the constraint. Verified on tb/bin/netstat: the string
 * /data/data/com.termux/files/usr/bin/bash is followed by exactly one NUL byte
 * and then unrelated data, so a longer replacement would overwrite whatever
 * follows. A stock bootstrap can therefore only be fully relocated to a package
 * name no longer than Termux's own.
 *
 *     Termux:  /data/data/com.termux                = 21 chars
 *     ours:    /data/data/<pkg>                     = 11 + len(pkg)
 *     => len(pkg) <= 10 for the .rodata paths to fit.
 *
 * Longer than that and we still relocate the RUNPATH, so the toolchain works,
 * but any program that hardcodes its own $PREFIX at compile time (env, nohup,
 * stdbuf, su, login, mktemp without TMPDIR) will still look under
 * /data/data/com.termux. We export HOME, TMPDIR and PREFIX everywhere so the
 * common cases are covered, and [relocate] reports exactly which binaries kept
 * stale references instead of leaving that to be discovered at runtime.
 */
object Relocator {

    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_FILES = "/data/data/com.termux/files"

    /**
     * The app data dir itself. Needed because plenty of scripts reference paths
     * outside $PREFIX -- termux-tools' `pkg` hardcodes
     * /data/data/com.termux/cache/apt/archives and .../cache/apt/pkgcache.bin,
     * which match neither TERMUX_PREFIX nor TERMUX_FILES and so survived
     * relocation, leaving apt's cache pointing into a directory we cannot write.
     */
    const val TERMUX_DATA_DIR = "/data/data/com.termux"

    /** Longest target prefix the .rodata strings can absorb. */
    val MAX_PREFIX_LEN = TERMUX_PREFIX.length

    const val ORIGIN_RUNPATH = "\$ORIGIN/../lib"

    data class Report(
        val elfPatched: Int,
        val textPatched: Int,
        val elfWithStaleRefs: List<String>,
        val fullRelocation: Boolean,
        val symlinksMade: Int = 0,
    ) {
        val staleCount: Int get() = elfWithStaleRefs.size
    }

    /** True when every hardcoded path in the bootstrap can be rewritten. */
    fun supportsFully(targetPrefix: String): Boolean =
        targetPrefix.length <= MAX_PREFIX_LEN

    /**
     * Rewrite [root]'s contents in place from [sourcePrefix] to [targetPrefix].
     * Symlinks are skipped; they are rebuilt by the caller from the archive's
     * SYMLINKS.txt.
     */
    fun relocate(root: File, sourcePrefix: String, targetPrefix: String): Report {
        var elfPatched = 0
        var textPatched = 0
        val stale = mutableListOf<String>()

        var seen = 0
        var elfs = 0
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            seen++
            when {
                isElf(file) -> {
                    elfs++
                    val bytes = file.readBytes()
                    val patched = replaceAll(bytes, "$sourcePrefix/lib", ORIGIN_RUNPATH)
                    var buf = patched

                    val fits = targetPrefix.length <= sourcePrefix.length
                    if (fits) {
                        buf = replaceAll(buf, sourcePrefix, targetPrefix)
                        buf = replaceAll(buf, TERMUX_FILES, targetPrefix.removeSuffix("/usr"))
                        buf = replaceAll(buf, TERMUX_DATA_DIR, targetPrefix.substringBefore("/files"))
                    }

                    if (!buf.contentEquals(bytes)) {
                        file.writeBytes(buf)
                        elfPatched++
                    }
                    if (indexOf(buf, sourcePrefix) >= 0) {
                        stale += file.relativeTo(root).path
                    }
                }

                isProbablyText(file) -> {
                    val text = file.readText()
                    if (text.contains(sourcePrefix)) {
                        file.writeText(
                            text.replace(sourcePrefix, targetPrefix)
                                .replace(TERMUX_FILES, targetPrefix.removeSuffix("/usr"))
                                .replace(TERMUX_DATA_DIR, targetPrefix.substringBefore("/files"))
                        )
                        textPatched++
                    }
                }
            }
        }

        android.util.Log.i(
            "tessl/relocator",
            "root=$root seen=$seen elfs=$elfs elfPatched=$elfPatched " +
                "textPatched=$textPatched stale=${stale.size} full=${
                    supportsFully(targetPrefix)
                } srcLen=${sourcePrefix.length} dstLen=${targetPrefix.length}",
        )
        if (elfs > 0 && elfPatched == 0) {
            android.util.Log.e(
                "tessl/relocator",
                "found $elfs ELFs but patched none -- source string never matched",
            )
        }
        return Report(
            elfPatched = elfPatched,
            textPatched = textPatched,
            elfWithStaleRefs = stale,
            fullRelocation = supportsFully(targetPrefix),
        )
    }

    private fun isElf(f: File): Boolean = try {
        f.inputStream().use { s ->
            val m = ByteArray(4)
            if (s.read(m) != 4) return false
            m[0] == 0x7F.toByte() && m[1] == 'E'.code.toByte() &&
                m[2] == 'L'.code.toByte() && m[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    private fun isProbablyText(f: File): Boolean = try {
        if (f.length() > 4L * 1024 * 1024) return false
        val head = RandomAccessFile(f, "r").use { raf ->
            ByteArray(minOf(1024, raf.length().toInt())).also { raf.readFully(it) }
        }
        // Reject anything with a NUL in the first block.
        head.none { it == 0.toByte() }
    } catch (_: Exception) {
        false
    }

    /**
     * Replace [from] with [to] in place.
     *
     * The result is only valid because every consumer here reads a
     * NUL-terminated string: a shorter replacement is written followed by an
     * explicit NUL, and the bytes after it become unreachable rather than
     * corrupted. A longer replacement is refused by the caller's length check.
     */
    private fun replaceAll(src: ByteArray, from: String, to: String): ByteArray {
        val f = from.toByteArray(Charsets.UTF_8)
        if (f.isEmpty()) return src
        val t = to.toByteArray(Charsets.UTF_8)
        if (t.size > f.size) return src

        // MUST copy: this mutates in place, and the caller compares the result
        // against the original with contentEquals to decide whether to write.
        // Returning src itself made that comparison trivially true, so nothing
        // was ever written: 338 ELFs found, 0 patched, no error reported.
        val buf = src.copyOf()
        var idx = indexOf(buf, from)
        while (idx >= 0) {
            System.arraycopy(t, 0, buf, idx, t.size)
            // NUL-terminate immediately after the replacement.
            buf[idx + t.size] = 0
            idx = indexOf(buf, from, idx + t.size)
        }
        return buf
    }

    private fun indexOf(hay: ByteArray, needle: String, from: Int = 0): Int {
        val n = needle.toByteArray(Charsets.UTF_8)
        outer@ for (i in from..hay.size - n.size) {
            for (j in n.indices) if (hay[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }
}
