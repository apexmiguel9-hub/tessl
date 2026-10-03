package io.github.apexmiguel9.termux.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle as TermStyle
import io.github.apexmiguel9.termux.session.TerminalSession
import kotlin.math.max

/**
 * Renders the emulator screen into a Compose Canvas.
 *
 * FORK NOTE: Termux rasterises with `TerminalRenderer` into a Bitmap that
 * `TerminalView` blits, which is correct for a View hierarchy. Going through
 * Compose's text layout instead costs a per-frame measure but removes the View
 * from the tree entirely, which is what makes translucency, backdrop blur and
 * arbitrary pane scaling possible later.
 *
 * Consecutive cells sharing a style are batched into one `drawText`, so a
 * typical prompt is a handful of draw calls, not one per cell.
 */
@Composable
fun TerminalCanvas(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 14,
    background: Color = Color(0xFF0B0B0F),
    foreground: Color = Color(0xFFD6D6DE),
    cursorColor: Color = Color(0xFFD6D6DE),
) {
    val measurer = rememberTextMeasurer()
    val baseStyle = remember(fontSizeSp, foreground) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, color = foreground)
    }
    val cell: IntSize = remember(fontSizeSp, baseStyle) {
        val m = measurer.measure("M", baseStyle)
        IntSize(max(1, m.size.width), max(1, m.size.height))
    }

    // Size the grid from the actual view, then resize the emulator to match.
    // It was created at a hardcoded 80x24, which on a 2400x1080 landscape
    // window drew into a 720x432 corner and left the rest black.
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(box, cell) {
        if (box.width > 0 && cell.width > 0) {
            val cols = (box.width / cell.width).coerceIn(20, 400)
            val rows = (box.height / cell.height).coerceIn(5, 200)
            session.resize(rows, cols, cell.width, cell.height)
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(background)
            .onSizeChanged { box = it }
    ) {
        drawScreen(session, cell.width, cell.height, defaultFg = foreground, cursorColor, baseStyle, measurer)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScreen(
    session: TerminalSession,
    cw: Int,
    ch: Int,
    defaultFg: Color,
    cursorColor: Color,
    baseStyle: TextStyle,
    measurer: TextMeasurer,
) {
    // Read the revision here so the draw scope is invalidated whenever the
    // pty produces output. Without this the canvas draws once and never again.
    val rev = session.revision
    val emu: TerminalEmulator = session.snapshot()
    val screen = emu.screen
    val cols = emu.columns
    val rows = emu.rows
    val palette: IntArray = emu.mColors.mCurrentColors

    if (rev < 0) return   // unreachable; keeps `rev` live for invalidation

    for (y in 0 until rows) {
        val row: TerminalRow = screen.getLine(y) ?: continue
        val used = row.spaceUsed
        var x = 0

        while (x < cols) {
            val style = row.getStyle(x)
            val start = x
            val sb = StringBuilder()
            while (x < cols && row.getStyle(x) == style) {
                // findStartOfColumn returns the index INTO mText for that
                // column (it walks the text accumulating wcwidth), so for
                // plain ASCII it equals the column. Subtracting it gave a
                // constant 0, so every cell in a run rendered mText[0] and a
                // prompt like "$ " printed once per column.
                val ci = row.findStartOfColumn(x)
                sb.append(if (ci in 0 until used) row.mText[ci] else ' ')
                x++
            }
            val text = sb.toString()
            if (text.isEmpty()) continue

            var fg = resolveFg(palette, style, defaultFg)
            var bg = resolveBg(palette, style)
            if (TermStyle.isInverse(style)) {
                val t = fg
                fg = if (bg == Color.Unspecified) defaultFg else bg
                bg = t
            }
            if (TermStyle.isBold(style) && fg == defaultFg) {
                fg = Color.White
            }

            val px = start * cw.toFloat()
            val py = y * ch.toFloat()

            if (bg != Color.Unspecified) {
                drawRect(
                    color = bg,
                    topLeft = Offset(px, py),
                    size = Size(text.length * cw.toFloat(), ch.toFloat()),
                )
            }

            if (text.isNotBlank()) {
                val drawStyle = baseStyle.copy(
                    color = fg,
                    fontWeight = if (TermStyle.isBold(style)) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (TermStyle.isUnderline(style)) TextDecoration.Underline else null,
                )
                drawText(measurer.measure(text, drawStyle), topLeft = Offset(px, py))
            }
        }
    }

    // Cursor is drawn last so it sits above the cell it occupies.
    if (emu.shouldCursorBeVisible() && emu.cursorRow in 0 until rows && emu.cursorCol in 0 until cols) {
        drawRect(
            color = cursorColor,
            topLeft = Offset(emu.cursorCol * cw.toFloat(), emu.cursorRow * ch.toFloat()),
            size = Size(cw.toFloat(), ch.toFloat()),
        )
    }
}

private fun resolveFg(palette: IntArray, style: Long, default: Color): Color =
    if (TermStyle.isTrueColorForeground(style)) {
        Color(0xFF000000.toInt() or TermStyle.foregroundRgb(style))
    } else {
        paletteColor(palette, TermStyle.foregroundIndex(style), default)
    }

private fun resolveBg(palette: IntArray, style: Long): Color =
    if (TermStyle.isTrueColorBackground(style)) {
        Color(0xFF000000.toInt() or TermStyle.backgroundRgb(style))
    } else {
        paletteColor(palette, TermStyle.backgroundIndex(style), Color.Unspecified)
    }

/** TerminalColors indexes 256/257/258 are fg/bg/cursor; 0..255 are the palette. */
private fun paletteColor(palette: IntArray, index: Int, fallback: Color): Color {
    if (index == TermStyle.COLOR_INDEX_FOREGROUND || index == TermStyle.COLOR_INDEX_BACKGROUND ||
        index == TermStyle.COLOR_INDEX_CURSOR
    ) {
        return fallback
    }
    if (index < 0 || index >= palette.size) return fallback
    val argb = palette[index]
    // The emulator uses -1 / 0 to mean "unset".
    return if (argb == -1 || argb == 0) fallback else Color(argb)
}
