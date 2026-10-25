# ClearFrameTV

A quiet home screen for an Android TV, in the style of [CleanFrame](https://github.com/nthnclrk/CleanFrame): a photograph, the time, and a glass dock of your apps. Built for one TV: a Sony BRAVIA 4K 2015 on Android 8.0 (API 26).

It is a native Android rewrite. CleanFrame itself is a Samsung Tizen web app and does not run on Android.

## What works (0.1.0)

- Photo wallpaper with CleanFrame's pre-blurred glass dock. Six bundled photos.
- 12-hour clock, top left.
- Dock of apps: YouTube, Netflix, Prime Video and StbEmu (free or pro, whichever is installed). Left/Right moves, OK opens.
- Up reaches three controls: Next wallpaper, All apps, Settings.
- All apps: OK opens an app; hold OK to add it to the dock or remove it.
- Settings: TV settings. "Clean up TV" is listed but not built yet.

Not yet: reordering the dock, the TV clean-up (turning off preinstalled apps, from TV Detox), making Home open this app without a computer.

## Build

GitHub Actions builds `clearframe.apk` on every push (see the run's `clearframe-apk` artifact). It also installs the APK on an Android TV 8.0 emulator and saves screenshots to the `ci-screenshots` branch.

Locally: Java 17, Android SDK `platforms;android-34` and `build-tools;34.0.0`, then `sh build.sh`.

To keep the same signing key between builds, add two repository secrets: `KEYSTORE_B64` (a base64-encoded `.jks`) and `KEYSTORE_PASS`. Without them each build is signed with a throwaway key, and installing a newer build needs the old one uninstalled first.

## Credits

Design, wallpapers and icons from CleanFrame by Nathan Clark (MIT). Build approach and clean-up rules from [TV Detox](https://github.com/Ansub/tv-detox) by Ansub Khan (MIT). Full list in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
