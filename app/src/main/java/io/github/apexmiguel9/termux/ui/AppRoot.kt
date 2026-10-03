package io.github.apexmiguel9.termux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.apexmiguel9.termux.session.TerminalSession

enum class Section(val label: String) {
    Terminal("Terminal"),
    Gui("GUI"),
    Browser("Browser"),
    Settings("Settings"),
}

/**
 * Root layout: the terminal plus a persistent bottom bar.
 *
 * The point of the bar is that `mkdir`, `cd`, `rm` and `cat` stop being
 * something you have to remember. Files, the Linux GUI and a browser are one tap
 * away instead of a shell pipeline.
 */
@Composable
fun AppRoot(
    sessions: List<TerminalSession>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onKey: (TerminalSession, Int, Int) -> Unit,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(Section.Terminal) }

    Box(modifier.fillMaxSize().background(Color(0xFF08080B))) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    Section.Terminal -> TerminalShell(
                        sessions = sessions,
                        activeId = activeId,
                        onSelect = onSelect,
                        onClose = onClose,
                        onNew = onNew,
                        onKey = onKey,
                    )
                    Section.Gui -> Placeholder("GUI", "Linux apps, X11 / Wayland surface.\nNot wired up yet.")
                    Section.Browser -> Placeholder("Browser", "Embedded web view.\nNot wired up yet.")
                    Section.Settings -> Placeholder("Settings", "Runtimes, font, keys, themes.\nNot wired up yet.")
                }
            }
            BottomBar(section) { section = it }
        }
    }
}

@Composable
private fun BottomBar(current: Section, onPick: (Section) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF121218))
            .navigationBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(52.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Section.entries.forEach { s ->
            val selected = s == current
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFF23232E) else Color.Transparent)
                    .clickable { onPick(s) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .size(width = 76.dp, height = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    s.label,
                    fontSize = 12.sp,
                    color = if (selected) Color(0xFFEDEFF6) else Color(0xFF7A7A85),
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(width = 18.dp, height = 2.dp)
                        .background(if (selected) Color(0xFF7AA2F7) else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun Placeholder(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopStart) {
        Column {
            Text(title, color = Color(0xFFE4E4EC), fontSize = 20.sp)
            Text(
                body,
                color = Color(0xFF7A7A85),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
