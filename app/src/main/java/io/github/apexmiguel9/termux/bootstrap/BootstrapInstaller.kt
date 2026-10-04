package io.github.apexmiguel9.termux.bootstrap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks a Termux bootstrap, then relocates it to our prefix.
 *
 * The archive layout is relative to $PREFIX (no leading slash), so unpacking is
 * just `zip -> prefixDir`. Symlinks are not representable in a zip and are
 * carried in a `SYMLINKS.txt` member, one `link←target` pair per line; the
 * installer recreates them afterwards. This mirrors what Termux's own
 * TermuxInstaller.java does, minus the Activity/AlertDialog plumbing.
 */
class BootstrapInstaller(private val ctx: Context) {

    sealed interface Result {
        data object AlreadyInstalled : Result
        data class Done(val report: Relocator.Report, val packages: Int) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * @param archiveUrl where the bootstrap zip lives. Point this at our own
     *   fork's release once it exists; until then Termux's published release
     *   works because [Relocator] rewrites the prefix.
     */
    suspend fun install(
        archiveUrl: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val paths = AppPaths.get(ctx)
        if (paths.isPrefixInstalled) return@withContext Result.AlreadyInstalled

        val tmp = File(paths.tmpDir, "bootstrap-install")
        runCatching {
            tmp.deleteRecursively()
            tmp.mkdirs()

            val zip = File(paths.downloadDir(), "bootstrap.zip")
            if (!zip.exists() || zip.length() < 1_000_000) {
                zip.parentFile?.mkdirs()
                download(archiveUrl, zip)
            }

            val (files, symlinks) = unzip(zip, paths.prefixDir, onProgress)

            // The zip cannot carry symlinks; without these there is no bash.
            val made = makeSymlinks(paths.prefixDir, symlinks)

            val report = Relocator.relocate(
                root = paths.prefixDir,
                sourcePrefix = Relocator.TERMUX_PREFIX,
                targetPrefix = paths.prefixDir.absolutePath,
            )

            exportTlsEnv(paths.prefixDir, paths.prefixDir.absolutePath)
            paths.ensureDirs()
            Result.Done(report.copy(symlinksMade = made), files)
        }.getOrElse {
            // The UI only shows the message; without this a "Stream closed"
            // with no context is undebuggable.
            android.util.Log.e("tessl/bootstrap", "install failed", it)
            Result.Failed("${it::class.java.simpleName}: ${it.message}")
        }
            .also { tmp.deleteRecursively() }
    }

    /**
     * The one consequence of a package name longer than Termux's.
     *
     * openssl carries /data/data/com.termux/files/usr/etc/tls/cert.pem in
     * .rodata. The relocator can only replace such a string with one that is no
     * longer (48 bytes available), and ours would be 66, so the CA bundle path
     * stays wrong and every TLS handshake fails:
     *
     *   error adding trust anchors from file:
     *     /data/data/com.termux/files/usr/etc/tls/cert.pem
     *
     * Every consumer below honours an env var over the compiled-in default, so
     * exporting them from the login profile is enough. The permanent fix is a
     * package name of 10 characters or fewer, which makes the string fit.
     */
    private fun exportTlsEnv(prefixDir: File, prefix: String) {
        val d = "$"  // Kotlin raw strings do not interpolate, be explicit anyway
        val block = """
            |# tessl: the compiled-in CA bundle path lives under /data/data/com.termux
            |# and cannot be rewritten in place when the package name is longer.
            |_t=""
            |for _v in cert.pem ca-certificates.crt; do
            |  if [ -f "${d}PREFIX/etc/tls/${d}_v" ]; then _t="${d}PREFIX/etc/tls/${d}_v"; break; fi
            |done
            |[ -n "${d}_t" ] || _t="${d}PREFIX/etc/tls/cert.pem"
            |export SSL_CERT_FILE="${d}_t" CURL_CA_BUNDLE="${d}_t" REQUESTS_CA_BUNDLE="${d}_t"
            |export GIT_SSL_CAINFO="${d}_t" NODE_EXTRA_CA_CERTS="${d}_t"
            |unset _v _t
        |""".trimMargin()

        for (name in listOf("profile", "bash.bashrc")) {
            val f = File(prefixDir, "etc/$name")
            if (!f.exists()) continue
            val cur = f.readText()
            if (cur.contains("tessl: the compiled-in CA bundle path")) continue
            runCatching { f.appendText("\n$block\n") }
        }
        android.util.Log.i("tessl/bootstrap", "TLS env exported to login profile")
    }

    private fun makeSymlinks(prefixDir: File, symlinks: List<Pair<String, String>>): Int {
        var made = 0
        var skipped = 0
        var failed = 0
        val failures = mutableListOf<String>()

        for ((targetName, linkPath) in symlinks) {
            // generate-bootstraps.sh writes SYMLINKS.txt as
            //   "<readlink output> <- U+2190 <path relative to $PREFIX>"
            // because it iterates `find . -type l` and echoes
            // "$(readlink "$link")<-${link}". So parts[1] is the LINK and
            // parts[0] is the TARGET:
            //
            //   "libreadline.so.8 <- ./lib/libreadline.so"
            //     link   = $PREFIX/lib/libreadline.so
            //     target = libreadline.so.8  (relative to the link's dir)
            //
            // which is exactly what a live Termux has:
            //   lib/libreadline.so -> libreadline.so.8
            // I had these the other way round, so every link pointed at a
            // path that was never created and bash died with
            // "library libreadline.so.8 not found".
            val link = File(prefixDir, linkPath)
            val target = File(link.parentFile, targetName)
            if (!link.path.startsWith(prefixDir.path)) {
                failed++
                failures += "escapes prefix: ${link.name}"
                continue
            }
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) {
                // A real file (e.g. libz.so.1.3.2) or a link we already made.
                skipped++
                continue
            }
            try {
                link.parentFile?.mkdirs()
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(),
                    target.toPath(),
                )
                made++
            } catch (e: Exception) {
                failed++
                if (failures.size < 8) failures += "${link.name}: ${e.message}"
            }
        }

        android.util.Log.i(
            "tessl/bootstrap",
            "symlinks made=$made skipped=$skipped failed=$failed" +
                if (failures.isEmpty()) "" else " e.g. $failures",
        )
        return made
    }

    private fun download(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            android.util.Log.i("tessl/bootstrap", "GET $url")
            if (conn.responseCode !in 200..299) {
                android.util.Log.e("tessl/bootstrap", "HTTP ${conn.responseCode} for $url")
                error("HTTP ${conn.responseCode} for $url")
            }
            android.util.Log.i(
                "tessl/bootstrap",
                "HTTP ${conn.responseCode} len=${conn.contentLengthLong} final=${conn.url}",
            )
            val total = conn.contentLengthLong
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** @return files written and symlink directives found. */
    private fun unzip(
        zip: File,
        prefixDir: File,
        onProgress: (Int, Int) -> Unit,
    ): Pair<Int, List<Pair<String, String>>> {
        var count = 0
        val symlinks = mutableListOf<Pair<String, String>>()

        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "SYMLINKS.txt" -> {
                        // NOT bufferedReader(): forEachLine closes the reader it
                        // creates, which closes the underlying ZipInputStream,
                        // and the next closeEntry() throws "Stream closed".
                        // readBytes() stops at the entry boundary instead.
                        val text = String(zin.readBytes(), Charsets.UTF_8)
                        text.lineSequence().forEach { line ->
                            val parts = line.split('\u2190')
                            if (parts.size == 2 && parts[0].isNotBlank()) {
                                symlinks += parts[0].trim() to parts[1].trim()
                            }
                        }
                    }
                    name.endsWith("/") -> Unit
                    else -> {
                        val out = File(prefixDir, name)
                        // Refuse to escape the prefix: zip-slip.
                        val canonical = out.canonicalPath
                        if (!canonical.startsWith(prefixDir.canonicalPath + File.separator)) {
                            error("zip entry escapes prefix: $name")
                        }
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            copy(zin, fos)
                        }
                        out.setReadable(true, false)
                        out.setExecutable(true, false)
                        count++
                        if (count % 500 == 0) onProgress(count, 0)
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        onProgress(count, count)
        return count to symlinks
    }

    private fun copy(src: InputStream, dst: FileOutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            dst.write(buf, 0, n)
        }
    }
}
