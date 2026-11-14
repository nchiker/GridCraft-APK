# GridCraft for Android (unofficial)

A small Android app for viewing spreadsheets and making minor edits on a phone. It runs ArtCraft's
[GridCraft](https://github.com/storytold/gridcraft) unchanged, from GridCraft's official web release, and adds a
phone-sized bar and menu around it. This is a personal project: it is not made, endorsed or supported by ArtCraft.

## What it does

- Opens `.xlsx`, `.xls`, `.xlsm`, `.xlsb`, `.ods` and `.csv` files from Android's file picker or from other apps
  (Files, cloud drives, email attachments).
- Shows every sheet at 200% for a phone screen; each sheet's own zoom is put back in the file when saving.
- A bar with Menu, Open, Undo, Redo and Save, and a short menu: Edit (Cut, Copy, Paste, Delete), Text (Bold,
  Italic), Fill Color, Font Color, Numbers, Rows & Columns, Find…, Format Cells…, Open…, Recent Files, Save As…, Close.
- Save writes back to the same `.xlsx`; other formats and read-only sources ask where to save an `.xlsx`.
- Asks before throwing away unsaved changes (Back, Open, Close, Recent Files).

## How it is built

- `gridcraft.version` pins a GridCraft web release and its SHA-256. The build downloads it, checks the checksum
  against the pin and the release's `SHA256SUMS.txt`, and copies it into the app's assets unchanged
  (`scripts/fetch-gridcraft.sh`).
- `android/app/src/main/web/` is the page around it: GridCraft's documented page host mode (`data-host="page"`,
  `gridcraft:open` / `gridcraft:save` events) and its `window.gridcraft` command API.
- `MainActivity.kt` is the Android side: the bar and menu, opening and saving through Android's storage APIs.
- The app has no internet permission; everything is served from the APK.

Build: `source env.sh && cd android && ./gradlew :app:assembleDebug` (JDK 21 and the Android SDK; paths in `env.sh`).

## Licences

GridCraft is MIT OR Apache-2.0 by its authors; its LICENSE, NOTICE and ATTRIBUTION files ship in the app's assets.
The bar icons are Material icons (Apache-2.0).
