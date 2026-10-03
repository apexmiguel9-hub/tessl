package io.github.apexmiguel9.termux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.apexmiguel9.termux.session.TerminalSession

/**
 * App shell: a tab strip over one active terminal.
 *
 * Termux has tabs and no splitting. Split/grid layouts land here rather than
 * in the session, because [TerminalSession] has no idea it is being drawn --
 * that separation is what lets the same session be shown in a tab, a split,
 * or a thumbnail.
 */
@Composable
fun TerminalShell(
    sessions: List<TerminalSession>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onKey: (TerminalSession, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = sessions.firstOrNull { it.id == activeId }

    Column(modifier.fillMaxSize().background(Bg)) {
        SessionBar(
            sessions = sessions,
            activeId = activeId,
            onSelect = onSelect,
            onClose = onClose,
            onNew = onNew,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (active == null) {
                EmptyState(onNew)
            } else {
                key(active.id) {
                    TerminalCanvas(
                        session = active,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Transparent, full-size: it exists only to hold focus and
                    // swallow key events for the active session.
                    KeyCapture(
                        onKey = { code, meta -> onKey(active, code, meta) },
                        onText = { t -> active.writeText(t) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private val Bg = Color(0xFF0B0B0F)
private val BarBg = Color(0xFF16161C)
private val TabIdle = Color(0xFF1B1B22)
private val TabActive = Color(0xFF23232C)
private val Fg = Color(0xFFE4E4EC)
private val Dim = Color(0xFF7A7A85)

@Composable
private fun SessionBar(
    sessions: List<TerminalSession>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
) {
    Surface(color = BarBg) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    val selected = s.id == activeId
                    val dead = s.exitCode != null
                    Row(
                        Modifier
                            .background(
                                if (selected) TabActive else TabIdle,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { onSelect(s.id) }
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = s.title.ifBlank { if (dead) "exited ${s.exitCode}" else s.runtime.displayName },
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (dead) Dim else Fg,
                            maxLines = 1,
                        )
                        Text(
                            text = " ✕",
                            fontSize = 11.sp,
                            color = Dim,
                            modifier = Modifier
                                .clickable { onClose(s.id) }
                                .padding(start = 2.dp),
                        )
                    }
                }
            }
            Button(onClick = onNew, modifier = Modifier.padding(end = 6.dp)) {
                Text("+", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("tessl", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text(
                "no session",
                color = Dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            Button(onClick = onNew) { Text("new session") }
        }
    }
}
