package io.github.apexmiguel9.termux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.apexmiguel9.termux.bootstrap.AppPaths
import io.github.apexmiguel9.termux.bootstrap.BootstrapInstaller
import io.github.apexmiguel9.termux.bootstrap.Relocator

/**
 * First-run gate: there is no shell until the bionic prefix exists.
 *
 * Reports what the relocator actually did rather than claiming success. When
 * the package name is longer than Termux's, some binaries keep absolute
 * references to /data/data/com.termux and that is surfaced, because silently
 * working "most of the time" is how you debug it at 2am instead of at install.
 */
@Composable
fun BootstrapGate(
    paths: AppPaths,
    archiveUrl: String,
    onReady: () -> Unit,
) {
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(paths.isPrefixInstalled) }
    var files by remember { mutableStateOf(0) }
    var symlinks by remember { mutableStateOf(0) }
    var elf by remember { mutableStateOf(0) }
    var stale by remember { mutableStateOf(0) }
    var full by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    if (done) {
        LaunchedReady(onReady)
        return
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("tessl", color = Color(0xFFE4E4EC), fontSize = 26.sp)
        Text(
            "bionic prefix",
            color = Color(0xFF7A7A85),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 22.dp),
        )

        if (running) {
            Text(
                if (files > 0) "unpacking $files files" else "downloading...",
                color = Color(0xFFB9B9C6),
                fontSize = 13.sp,
            )
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                color = Color(0xFF7AA2F7),
            )
        } else {
            Text(
                paths.prefixDir.absolutePath,
                color = Color(0xFF7A7A85),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text(
                "stock Termux bootstrap, relocated at install time",
                color = Color(0xFF7A7A85),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 18.dp),
            )

            Button(
                onClick = {
                    running = true
                    error = null
                    val installer = BootstrapInstaller(paths.context())
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        when (val r = installer.install(archiveUrl) { d, _ -> files = d }) {
                            is BootstrapInstaller.Result.AlreadyInstalled -> done = true
                            is BootstrapInstaller.Result.Done -> {
                                elf = r.report.elfPatched
                                symlinks = r.report.symlinksMade
                                stale = r.report.staleCount
                                full = r.report.fullRelocation
                                done = true
                            }
                            is BootstrapInstaller.Result.Failed -> {
                                error = r.message
                                running = false
                            }
                        }
                    }
                },
                enabled = !running,
            ) { Text("install (31 MB)") }

            error?.let {
                Text(
                    it,
                    color = Color(0xFFF7768E),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }

    if (done && files > 0) {
        // Summary line, shown by the caller through onReady's session tab.
        Log.summary = "$files files, $symlinks symlinks, $elf ELFs relocated" +
            if (full) "" else ", $stale ELFs keep /data/data/com.termux"
    }
}

@Composable
private fun LaunchedReady(onReady: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onReady() }
}

object Log {
    @Volatile
    var summary: String = ""
}
