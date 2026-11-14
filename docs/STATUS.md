# Status

## 2026-11-14: v0.1.0 released

GitHub release v0.1.0 (https://github.com/nchiker/GridCraft-APK/releases/tag/v0.1.0): `GridCraft-0.1.0.apk` + `SHA256SUMS`
(APK SHA-256 368f4a8c…732b, signed with the gridcraft key). Installed on the owner's phone 2026-11-14 at the owner's request.
Release steps: bump `versionCode`/`versionName` in `android/app/build.gradle.kts`, `./gradlew :app:assembleRelease`, copy to
`dist/GridCraft-<ver>.apk`, `sha256sum > dist/SHA256SUMS`, tag `v<ver>`, `gh release create` with both files.

## 2026-11-14: first build (0.1.0, debug only)

Works on the Pixel 4a (checked by hand):
- Starts with GridCraft 0.19.1 inside the app: no browser bar, no GridCraft title bar or File tab, ribbon collapsed,
  interface 85%, sheet 200%.
- Open (bar button / menu) uses Android's picker; the file opens at 200% on every sheet and the previous workbook is closed.
- Save writes back to the same .xlsx, with each sheet's own zoom restored in the file (checked by unzipping it).

Full menu test on the 4a (Sonnet agent, 2026-11-14): every menu item, Undo/Redo, Find…/Format Cells… + Back,
ribbon + Back, the unsaved-changes prompt (Back, Open and Close), Recent Files (incl. after a restart and a deleted file), Save in place, Save As…, rotation, paste from another app: all PASS.
Fixed after it: Undo could undo the app's own startup zoom (now one real edit per tap, skipping zoom/sheet-switch
entries); set-up failed when the app started with the screen off (now waits until visible).

Not yet checked: opening from other apps (Files, Filen, email), saving files that aren't .xlsx or can't
be written (should ask where to save), the unsaved-changes prompt, Back behaviour, copy/paste with other apps,
rotation, a large real-world-sized file.

Release signing: key made 2026-11-14 in `~/APK-Keys/gridcraft/` (cert SHA-256 dc3863a4…74c3, entry added to
`APK-Keys/README.txt`). Backed up with Key Vault (2026-11-14). `./gradlew :app:assembleRelease` signs with it
(9.7 MB APK).

Reported to ArtCraft (storytold/gridcraft), 2026-11-14:
- #4185 tab row cut off and not scrollable on narrow windows
- #4186 Format Cells dialog wider than a narrow window (long number format)
- #4187 status bar Count overlaps "Ready"
- #4188 `?chrome=` option to hide the ribbon for hosts with their own menu
- #4189 Excel-style "Show tabs only" (temporary ribbon)
- comment on #3849 (page host mode works for us in 0.19.1) and #2313 (zoom on the undo stack; a noundo fix is proposed).
When a fix lands in a release, update `gridcraft.version` and drop the matching workaround (host.js: interface 85%, undo skipping).
