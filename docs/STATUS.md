# Status

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

Ask ArtCraft for (owner reviews before anything is posted): a `?chrome=` option that hides the tab row/ribbon,
Excel-style "show tabs only" (ribbon closes after use), the tab row not scrolling on narrow screens, the Format Cells
dialog growing wider than a phone screen when a long number-format code is selected (e.g. Accounting), and the status
bar's Count/Sum text overlapping "Ready" on a narrow screen.
