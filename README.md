# tessl

Android terminal with a GUI-first shell, built from scratch on Kotlin/Compose.

## Architecture

Three layers, and the boundary between them is the whole design.

```
bionic host  (the APK, Kotlin/Compose + our JNI)
  |- pty, session, emulator host, GPU proxy  <- only this can touch the GPU
  |
  |- argv -> bionic prefix          (BionicRuntime)
  |- argv -> proot -> glibc rootfs  (ProotDistroRuntime)
```

`Runtime` is the seam. The pty, the VT emulator, and the Compose shell are
identical for both; only the argv and environment change.

## Why the emulator is vendored, not rewritten

`app/src/main/java/com/termux/terminal/` is Termux's terminal-emulator
(GPL-3.0), ~7.8k lines of Java that implement escape sequences, UTF-8,
wcwidth, scrollback, sixel and alt-screen. Kotlin and Java share a JVM, so
Kotlin calls it directly with no porting step.

It is forked in four documented ways, all in files carrying `FORK PATCH`:

| Change | Why |
|---|---|
| `TerminalSessionClient` -> `TerminalEmulatorClient` (5 methods) | upstream's version takes a `TerminalSession`, which loads `libtermux` and drags the whole Activity layer in |
| `TerminalBuffer.getLine(int)` added | `mLines` was package-private; an out-of-tree renderer cannot walk the screen |
| `TerminalRow.mText/mStyle/mSpaceUsed` public | same reason |
| `TextStyle` public decoders + `NORMAL` public | bit layout was documented but had no accessors |
| `Logger` writes to logcat directly | upstream forwarded to a client-side logging facade that no longer exists |

`JNI.java`, `TerminalSession.java` and `TerminalSessionClient.java` were
deleted: the pty here is `native/pty`, so `libtermux` is not used. The
`termux/termux-pty` repo is 404, so there was nothing to link against anyway.

## The pty is ours

`native/pty/` is POSIX C with no JNI, so it compiles and its test suite runs on
a plain host before any Android toolchain exists:

```sh
cc -Wall -Wextra -O2 -std=c11 -D_GNU_SOURCE -o test_pty \
   native/pty/test_pty.c native/pty/pty_core.c
./test_pty /bin/bash
```

bionic has no `forkpty()` and no `login_tty()`, which is why Termux had to
write its own. The two shims needed are documented at the top of `pty_core.c`.

The child gets `PR_SET_PDEATHSIG` so shells do not survive the app.

## Bootstrap

Binaries are built by a fork of `termux-packages` with
`TERMUX_APP__PACKAGE_NAME=io.github.apexmiguel9.termux`, so the prefix
`/data/data/io.github.apexmiguel9.termux/files/usr` is baked into the ELF
strings at compile time. It is a build-time contract, not something that can
be patched at install time: upstream `.deb`s contain `/data/data/com.termux`
in both the archive layout and the binary strings.

See `apexmiguel9-hub/termux-packages/.github/workflows/tessl-bootstrap.yml`.

## Licence

GPL-3.0. The vendored emulator is GPL-3.0, and the bootstrap packages are
GPL-3.0. All projects here are open source.
