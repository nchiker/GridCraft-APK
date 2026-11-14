# Status

## 2026-11-14: first build (0.1.0, debug only)

Works on the Pixel 4a (checked by hand):
- Starts with GridCraft 0.19.1 inside the app: no browser bar, no GridCraft title bar or File tab, ribbon collapsed,
  interface 85%, sheet 200%.
- Open (bar button / menu) uses Android's picker; the file opens at 200% on every sheet and the previous workbook is closed.
- Save writes back to the same .xlsx, with each sheet's own zoom restored in the file (checked by unzipping it).

Not yet checked: each menu item, opening from other apps (Files, Filen, email), saving files that aren't .xlsx or can't
be written (should ask where to save), the unsaved-changes prompt, Back behaviour, copy/paste with other apps,
rotation, a large real-world-sized file.

Not done: release signing key (`~/APK-Keys/gridcraft/`), release build.

Ask ArtCraft for (owner reviews before anything is posted): a `?chrome=` option that hides the tab row/ribbon,
Excel-style "show tabs only" (ribbon closes after use), and the tab row not scrolling on narrow screens.
