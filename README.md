# GridCraft for Android (personal build)

ArtCraft's [GridCraft](https://github.com/storytold/gridcraft) spreadsheet, running unchanged from its official web
release inside a small Android app that adds a phone-sized menu and opens/saves files on the phone.

GridCraft is MIT OR Apache-2.0 (its LICENSE and NOTICE files ship in the app's assets). Bar icons are Material icons
(Apache-2.0).

Build: `source env.sh && cd android && ./gradlew :app:assembleDebug`. The pinned GridCraft release
(`gridcraft.version`) is downloaded and checksum-verified during the build.
