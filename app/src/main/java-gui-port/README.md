# termux-gui server port (phase 4, parked)

Source vendored from https://github.com/termux/termux-gui (GPL-3.0), the server
side of its protobuf/JSON protocol, so a CLI program can drive native Android
views from inside our own APK.

It lives outside `src/main/java` so it does not break the build yet. What is
still missing, in the order ROADMAP.md lists:

1. `ConnectionHandler` still imports the protobuf handlers that were deleted.
   Rewire it to the JSON protocol only — `Protocol.md` documents it as a 4-byte
   big-endian length prefix per message, which needs no codegen.
2. `GUIConfigActivity` and the widget classes use the plugin's databinding
   layouts and `R.*`. Our Settings section replaces the config activity, so
   those files should be deleted rather than ported.
3. `GUIService` / `GUIActivity` / `HardwareBufferSurfaceView` need the plugin's
   resources (layouts, themes, strings) moved into `app/src/main/res/`.

What is already done:

- package renamed to `io.github.apexmiguel9.termux.gui`, zero `com.termux`
  references remain
- `hbuffers` (AHardwareBuffer receive) included; this is the GLES2 path and is
  why we need no GPU proxy
- protobuf subtree removed, which is ~1,500 lines we do not need
- the manifest already declares `.gui.GUIReceiver`, `.gui.GUIService` and
  `.gui.GUIActivity`, with the broadcast action `com.termux.gui/.GUIReceiver`
  that the upstream C library sends

To re-enable: move the `gui` directory back to
`src/main/java/io/github/apexmiguel9/termux/` and re-add the deps listed in the
git history of `app/build.gradle.kts` (gson, appcompat, constraintlayout,
material, legacy-support-v4, coroutines-android).
