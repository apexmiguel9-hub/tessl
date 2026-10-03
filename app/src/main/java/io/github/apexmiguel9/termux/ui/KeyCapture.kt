package io.github.apexmiguel9.termux.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView

/**
 * Routes hardware and IME key events to the session.
 *
 * Compose's `onKeyEvent` covers the soft keyboard only partially, so this also
 * installs a view-level `dispatchKeyEvent` hook: the terminal needs raw
 * keycodes with metaState (Ctrl/Alt/Shift), which the IME path does not expose.
 */
@Composable
fun KeyCapture(
    onKey: (keyCode: Int, metaState: Int) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(view) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN) {
                when (code) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_HOME,
                    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
                    -> {
                        onKey(code, ev.metaState)
                        true
                    }
                    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z,
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
                    -> {
                        onKey(code, ev.metaState)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        view.requestFocus()
    }

    androidx.compose.foundation.layout.Box(
        modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    when (e.key) {
                        Key.Enter -> { onText("\r"); true }
                        Key.Backspace -> { onText("\b"); true }
                        Key.Escape -> { onKey(KeyEvent.KEYCODE_ESCAPE, 0); true }
                        Key.Tab -> { onKey(KeyEvent.KEYCODE_TAB, 0); true }
                        Key.DirectionUp -> { onKey(KeyEvent.KEYCODE_DPAD_UP, 0); true }
                        Key.DirectionDown -> { onKey(KeyEvent.KEYCODE_DPAD_DOWN, 0); true }
                        Key.DirectionLeft -> { onKey(KeyEvent.KEYCODE_DPAD_LEFT, 0); true }
                        Key.DirectionRight -> { onKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0); true }
                        else -> false
                    }
                } else {
                    false
                }
            }
    )
}
