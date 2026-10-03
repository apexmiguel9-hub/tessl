package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Callbacks the {@link TerminalEmulator} needs from whoever is hosting it.
 *
 * FORK PATCH (vs. Termux): upstream this interface is
 * {@code TerminalSessionClient}, whose every method takes a
 * {@code TerminalSession}. That type owns a native library
 * ({@code System.loadLibrary("termux")}) and the whole Activity/Service layer,
 * which drags the entire app into the emulator module. Since the PTY here is
 * ours (native/pty, no libtermux), the emulator only needs these five things:
 * a logging sink, a cursor-style query, and change notifications.
 */
public interface TerminalEmulatorClient {

    /** Screen contents changed; the renderer should invalidate. */
    void onTextChanged();

    /** OSC 0/2 changed the window title. */
    void onTitleChanged(@Nullable String title);

    void onBell();

    void onColorsChanged();

    /** DECTCEM cursor visibility changed. */
    void onTerminalCursorStateChange(boolean visible);

    /** Preferred cursor style, or null to keep the current one. */
    @Nullable
    Integer getTerminalCursorStyle();
}
