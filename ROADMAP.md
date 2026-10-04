# tessl — roadmap

An Android terminal built around a GUI-first shell, with a bionic-minimal core
and glibc/Debian on top.

Priority order is deliberate: **the terminal must be genuinely good before any
GUI is layered on it.** Every later phase reads and writes the same filesystem
the shell does, so a shaky terminal makes every other phase worse.

Status legend: `[x]` done and verified on device · `[~]` in progress · `[ ]` pending

---

## Phase 0 — foundations `[x]`

Everything below depends on these, and they were all discovered the hard way.

| Item | State | Evidence |
|---|---|---|
| Own PTY in C (no `forkpty`/`login_tty` on bionic) | `[x]` | 10/10 host tests |
| `PR_SET_PDEATHSIG` so shells don't outlive the app | `[x]` | — |
| Vendored Termux `terminal-emulator`, decoupled from `libtermux` | `[x]` | 7.8k lines Java, 0 external deps |
| Compose renderer over `TerminalBuffer` | `[x]` | ANSI colours, alignment |
| `Runtime` seam: bionic vs proot, same UI | `[x]` | — |
| APK signing, CI on `ubuntu-26.04` | `[x]` | v2 scheme verified by `apksigner` |
| `targetSdk = 28` | `[x]` | see "the 28 rule" below |

### The three constraints that shaped everything

**1. `targetSdk` must be ≤ 28.** AOSP `private/app_neverallows.te`:

```
neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app
           } { app_data_file privapp_data_file }:file execute_no_trans;
```

Only apps with `targetSdk ≤ 28` get a domain allowed to `execve()` from their
own data dir. Everything newer runs as plain `untrusted_app` and every exec
returns `EACCES`. No manifest flag, permission or Java API works around it.
Consequence: the Play Store will not accept the build, so distribution is
F-Droid / GitHub.

**2. Stock Termux bootstrap *is* relocatable.** 338 ELF, 337 with
`RUNPATH=/data/data/com.termux/files/usr/lib`, `PT_INTERP=/system/bin/linker64`.
`$ORIGIN/../lib` is 14 bytes where the original is 35, so it fits in place
inside `DT_STRTAB`, and bionic does expand `$ORIGIN` (verified by running a
relocated `coreutils` on API 30). `SYMLINKS.txt` is `<target>←<link>`, link
paths relative to the target's directory.

**3. A package name longer than Termux's leaves ~200 stale ELF strings.** They
sit in `.rodata` with 1 byte of padding, so they cannot be grown in place. Most
are harmless because we export `HOME`/`TMPDIR`/`PREFIX`, but one is not: openssl
keeps the CA bundle path in `.rodata`, so **all TLS fails** and every HTTPS
mirror is unreachable. We export `SSL_CERT_FILE` and friends to work around it.
A package name of ≤ 10 characters makes the string fit and removes the whole
class of problem. **Decision pending.**

---

## Phase 1 — the terminal works properly `[~]`

Nothing else starts until this is solid.

- `[x]` shell spawns, prompt renders, input reaches the pty (IME delta receiver)
- `[x]` `ls -la` with correct columns, `echo`, ANSI SGR colours
- `[x]` resize, scrollback, cursor, `$HOME` as cwd
- `[ ]` **verify `pkg update` end to end** with the CA-bundle env fix
- `[ ]` swap bootstrap to our own build (`apexmiguel9-hub/termux-packages`) and
      re-verify; ours is 205/209 packages clean, the other 4 are now patched
- `[ ]` scrollback search across sessions (SQLite FTS5 over a scrollback file)
- `[ ]` extra keys row: Esc, Ctrl, Alt, Tab, arrows, PgUp/PgDn, `|`, `-`, `~`
- `[ ]` copy/paste selection, and paste that honours bracketed paste
- `[ ]` session persistence: restore sessions and scrollback after process death
- `[ ]` split and grid layouts, per-pane font size
- `[ ]` sixel and Kitty graphics protocol

---

## Phase 2 — files, live `[ ]`

The point is that `cp`, `mv`, `mkdir` and `cat` stop being something you have to
remember. This is **not** a separate filesystem: every operation goes through
the same shell in the same prefix, and the views update from the shell's view.

- `[ ]` file browser rooted at `$HOME`, plus the ability to change root
- `[ ]` **live**: a file created in the shell appears without a refresh, and
      vice versa. `inotify` on the prefix, feeding the same state the emulator
      already has
- `[ ]` every action is a real command, not a hidden API: create → `mkdir`/`touch`,
      delete → `rm`, move → `mv`, copy → `cp`, so muscle memory and scripts still
      agree with the UI
- `[ ]` import from Android (SAF `ACTION_OPEN_DOCUMENT`), which lands the file in
      the prefix and shows up in `ls`
- `[ ]` export/share to Android (`ACTION_CREATE_DOCUMENT`)
- `[ ]` built-in editor: syntax highlighting, save-on-focus-loss, create a `.py`
      or `.cpp` from the UI and it exists for the compiler immediately
- `[ ]` preview for images, video, audio
- `[ ]` show `ls`-style metadata (size, mtime, permissions) without shelling out
      per row

**Design constraint:** the browser must not become a second source of truth. One
`FileSystem` abstraction, one change log, and the terminal is just another
observer. If the browser can show a file the shell cannot, or hide one it can,
that's a bug.

---

## Phase 3 — a real glibc distro `[ ]`

The bionic prefix is deliberately minimal (109 packages: bash, coreutils, apt,
proot). Real software lives in glibc.

- `[ ]` rootfs extraction and management (Debian/Ubuntu), currently only
      `ProotDistroRuntime` exists as a stub
- `[ ]` seccomp-bpf proot, not ptrace: the ptrace build costs two context
      switches per intercepted syscall
- `[ ]` RAM control, which is the user's actual requirement:
      `MALLOC_ARENA_MAX=1`, `MALLOC_TRIM_THRESHOLD_`, cgroup v2 `memory.max` per
      session, lazy bind mounts
- `[ ]` measure it, don't guess: report peak RSS per session in Settings
- `[ ]] per-session runtime choice in the UI: this is what the `Runtime` seam is for

---

## Phase 4 — GUI `[~]` port started

Using [`termux/termux-gui`](https://github.com/termux/termux-gui) (GPL-3.0,
1186★, updated 2026-10-02) rather than X11 or Wayland.

```
guest program ── libtermuxgui (MPL-2.0, C/Python/Bash bindings)
                │  creates 2 AF_UNIX abstract sockets
                │  am broadcast -n <pkg>/.gui.GUIReceiver
                │     --es mainSocket X --es eventSocket Y
                ▼
             our app: GUIReceiver → GUIService → native Android views
                GLES2 via AHardwareBuffer_recvHandleFromUnixSocket
```

Why this and not X11:

- **no GPU proxy.** The guest draws into a hardware buffer that crosses over the
  socket, so the guest never needs `/dev/kgsl-3d0` or `/dev/mali0`. The whole
  Vortek plan was solving a problem this does not have.
- no X server, no Wayland compositor, no second APK
- native widgets (buttons, switches, EditText, scrolling, LinearLayout), WebView,
  notifications, widgets
- any language, via the official bindings

State: source ported to `app/src/main/java-gui-port/` (6,435 lines, package
renamed, zero `com.termux` references left) but parked outside the source set.

- `[ ]` port `ConnectionHandler` to the JSON protocol only, so protobuf codegen
      is unnecessary (JSON is 4-byte big-endian length prefixed; `Protocol.md`
      documents it)
- `[ ]` port the plugin's resources and layouts; `GUIConfigActivity` uses
      databinding and is being replaced by our Settings section
- `[ ]` `HardwareBufferSurfaceView` for GLES2 (the device here is MediaTek, so
      the driver path is PanVK/dzn, not Turnip)
- `[ ]` point the C library at our component: one string in
      `termux-gui-c`'s build, exactly like the `termux-tools` fix
- `[ ]` embed its Activities in our shell rather than as separate windows

---

## Phase 5 — our own keyboard `[ ]`

The stock IME works and is the fallback. The custom one is for people who want
PC-grade input on a phone.

- `[ ]` floating keyboard, toggled by a floating button, customisable opacity
      and height
- `[ ]` **keyboard mode**: full PC key set — Esc, Ctrl, Alt, Tab, arrows,
      Home/End, PgUp/PgDn, F1–F12, `|`, `-`, `~`, and a sticky-modifier row
- `[ ]` **shortcuts mode**: only the modifier panel (Ctrl/Shift/Alt/Esc/Tab/
      arrows) plus the stock Android keyboard underneath, so nothing is lost
- `[ ]` both modes feed the same IME-delta path that already works

---

## Section bar `[~]`

Bottom navigation is in: `[Terminal] [GUI] [Browser] [Settings]`. Terminal is
real; the rest are placeholders pending the phases above. Settings is where
runtime choice, font, keys, opacity and RAM limits go.

---

## Bugs found, for the record

Each of these blocked something visible and each was found by reading a log or a
screenshot rather than by reasoning.

| # | Bug | Symptom |
|---|---|---|
| 1 | `SYMLINKS.txt` read backwards | `libreadline.so.8 not found` |
| 2 | `replaceAll` mutated in place and returned the same reference | 338 ELF, 0 patched, no error |
| 3 | `bufferedReader()` closed the `ZipInputStream` | `IOException: Stream closed` |
| 4 | `/data/user/0` instead of `/data/data` | prefix 4 bytes too long |
| 5 | `processCodePoint(keyCode)` | `m` typed as `)` |
| 6 | `x - findStartOfColumn(x)` | screen full of `$` |
| 7 | `kill(pid, 0)` as an EOF test | thread at 100% CPU; a zombie still answers signal 0 |
| 8 | `buildorder.py` omits the seed package | bootstrap had **no apt** |
| 9 | `targetSdk 36` | `EACCES` on every exec |
| 10 | key events only, no IME receiver | soft keyboard typed nothing |
| 11 | no `chdir`, pen advanced by `charCount * cellWidth` | `ls` EACCES, drifting columns |