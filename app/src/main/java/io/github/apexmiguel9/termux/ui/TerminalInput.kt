package io.github.apexmiguel9.termux.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.sp
import android.view.inputmethod.InputMethodManager

/**
 * Receives text from the IME and hardware keys and puts them on the wire.
 *
 * WHY A HIDDEN TEXT FIELD: the soft keyboard does not deliver key codes, it
 * commits text through the IME. Handling only `onKeyEvent` therefore drops every
 * letter typed on the on-screen keyboard -- the shell showed a prompt and then
 * ignored input. So the terminal owns a zero-opacity [BasicTextField] that keeps
 * focus, and the delta between consecutive values is what goes to the pty:
 *
 *   text grew     -> send the appended characters
 *   text shrank   -> send one DEL per removed character
 *
 * `singleLine = false` is deliberate: it makes the IME's action key insert a
 * real newline, which is exactly what a shell wants, so Enter needs no special
 * case and Ctrl/Enter or Shift/Enter stay available on a hardware keyboard.
 */
@Composable
fun TerminalInput(
    onText: (String) -> Unit,
    onKey: (keyCode: Int, metaState: Int) -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
) {
    var pending by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val showKeyboard: () -> Unit = {
        keyboard?.show()
        runCatching {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // Hardware keyboards bypass the IME entirely and arrive as key events on
    // the view, so that path is kept alongside the field.
    LaunchedEffect(view) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val meta = event.metaState
            val ctrl = meta and KeyEvent.META_CTRL_ON != 0
            val alt = meta and KeyEvent.META_ALT_ON != 0
            when {
                // Let the field handle anything that is not a navigation or
                // editing key, so hardware typing still reaches the IME path.
                code == KeyEvent.KEYCODE_ENTER || ctrl || alt ||
                    code == KeyEvent.KEYCODE_TAB || code == KeyEvent.KEYCODE_ESCAPE ||
                    code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    code == KeyEvent.KEYCODE_MOVE_HOME || code == KeyEvent.KEYCODE_MOVE_END ||
                    code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN -> {
                    onKey(code, meta)
                    true
                }
                else -> false
            }
        }
    }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focus.requestFocus() }
            view.requestFocus()
            showKeyboard()
        }
    }

    BasicTextField(
        value = pending,
        onValueChange = { next ->
            val prev = pending
            when {
                next.length > prev.length && next.startsWith(prev) ->
                    onText(next.substring(prev.length))

                next.length < prev.length && prev.startsWith(next) ->
                    repeat(prev.length - next.length) { onText("\u007f") }

                else -> {
                    // IME replaced the whole buffer (autocorrect, candidate
                    // swap). Fall back to sending the new text as-is.
                    if (next.isNotEmpty()) onText(next)
                }
            }
            pending = next
        },
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focus),
    )
}

/** Call from a tap on the terminal to raise the IME again. */
@Composable
fun rememberKeyboardRaiser(): () -> Unit {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    return {
        keyboard?.show()
        runCatching {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
        Unit
    }
}
