package io.github.apexmiguel9.termux.bootstrap

import android.content.Context
import java.io.File

/**
 * Filesystem layout.
 *
 * These paths are not cosmetic. The bootstrap binaries are compiled with
 * `/data/data/io.github.apexmiguel9.termux/files/usr` baked into their ELF
 * strings, so the package name and this directory layout are a build-time
 * contract with termux-packages (scripts/properties.sh,
 * TERMUX_APP__PACKAGE_NAME). Changing applicationId without rebuilding the
 * bootstrap yields binaries looking for a prefix that does not exist.
 */
class AppPaths private constructor(private val appContext: Context) {

    val context: Context get() = appContext

    val filesDir = File(appContext.applicationInfo.dataDir, "files")
    val prefixDir = File(filesDir, "usr")
    val homeDir = File(filesDir, "home")
    val cacheDir = File(appContext.applicationInfo.dataDir, "cache")
    val tmpDir = File(prefixDir, "tmp")

    fun distroDir(id: String) = File(filesDir, "distro/$id")
    fun downloadDir() = File(cacheDir, "bootstrap")
    fun shellBin() = File(prefixDir, "bin/bash")
    fun prootBin() = File(prefixDir, "bin/proot")

    /** True when the bionic prefix has actually been unpacked. */
    val isPrefixInstalled: Boolean
        get() = shellBin().canExecute()

    fun ensureDirs() {
        listOf(filesDir, prefixDir, homeDir, cacheDir, tmpDir).forEach { it.mkdirs() }
    }

    companion object {
        const val PACKAGE = "io.github.apexmiguel9.termux"

        /** Must stay byte-identical to TERMUX_APP__PACKAGE_NAME in termux-packages. */
        const val COMPILED_PREFIX = "/data/data/io.github.apexmiguel9.termux/files/usr"

        /**
         * The prefix the *stock* Termux bootstrap is compiled for, and the one
         * [Relocator] rewrites from.
         */
        const val STOCK_TERMUX_PREFIX = Relocator.TERMUX_PREFIX

        /** This app's prefix at runtime. Must match applicationId. */
        val runtimePrefix: String get() = COMPILED_PREFIX

        /** termux-packages validates TERMUX__PREFIX at 90 bytes including NUL. */
        const val PREFIX_MAX_LEN = 90

        /** Ours is 49 bytes, so the package name can still grow. */
        val HEADROOM = PREFIX_MAX_LEN - COMPILED_PREFIX.length

        @Volatile
        private var instance: AppPaths? = null

        fun get(ctx: Context): AppPaths =
            instance ?: synchronized(this) {
                instance ?: AppPaths(ctx.applicationContext).also { instance = it }
            }
    }
}
