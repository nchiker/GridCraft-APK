# GridCraft-APK notes for Claude

Read `docs/STATUS.md` first. This folder is its OWN git repo (branch `main`), not part of `~/Projects`.

- What it is: an Android app that runs ArtCraft's GridCraft **web build, unchanged**, in a WebView, plus a slim bar
  (menu, file name, Open, Undo, Redo, Save). The owner uses it to view Excel files and make minor edits.
- Never patch GridCraft. Everything goes through what its HOSTING.md documents: page host mode (`data-host="page"`,
  `gridcraft:open/save/dirty` events on the canvas), `?chrome=none`, and `window.gridcraft.execute/request/docInspect/uiInspect`.
  GridCraft source for reference: https://github.com/storytold/gridcraft (tag `v<version>`).
- Updating GridCraft: change `gridcraft.version` (version + the zip's SHA-256 from the release's SHA256SUMS.txt), rebuild.
  The build runs `scripts/fetch-gridcraft.sh`, which downloads, checks the checksum, and fills `assets/gridcraft/` (git-ignored).
- Our code: `android/app/src/main/web/host.js` (page side), `host.template.html`, and `MainActivity.kt` (everything Android).
- Owner's settings: sheet zoom 200% on screen; each sheet's own zoom is put back when saving. Interface size 85%. Ribbon collapsed.
  A short hand-picked menu (`menuItems` in MainActivity.kt), not GridCraft's full command list.
- Build: `source env.sh; cd android; ./gradlew :app:assembleDebug` (debug id `app.gridcraft.viewer.debug`).
- Signing key: `~/APK-Keys/gridcraft/` (owner's rule: all keys there; the owner's Key Vault backs it up). Never commit it.
- Test phone: Pixel 4a `<test-phone-serial>` (always `adb -s`; adb is `~/.local/share/stack-android-sdk/platform-tools/adb`).
  The owner's real phone (Pixel 10a) is hands off. The 4a holds real documents: test only with synthetic files
  (named `gridcraft-test-*`), and never share screenshots that show other files.
- Live debugging: the debug build allows WebView remote debugging. `adb forward tcp:9334 localabstract:webview_devtools_remote_<pid>`,
  then CDP (Node's built-in WebSocket) to run `window.gridcraft.*` and `GC.*` in the page.
- Use cheaper models (sonnet/haiku subagents) for routine testing and doc updates (owner's rule).
