# ClearFrameTV

A quiet home screen for an Android TV, in the style of [CleanFrame](https://github.com/nthnclrk/CleanFrame): a photograph, the time, and a glass dock of your apps. Built for one TV: a Sony BRAVIA 4K 2015 on Android 8.0 (API 26).

It is a native Android rewrite. CleanFrame itself is a Samsung Tizen web app and does not run on Android.

## What works (0.2.0)

- Photo wallpaper with CleanFrame's pre-blurred glass dock. Six bundled photos.
- 12-hour clock, top left.
- Dock of apps: YouTube, Netflix, Prime Video and StbEmu (free or pro, whichever is installed). Left/Right moves, OK opens.
- Up reaches three controls: Next wallpaper, All apps, Settings.
- All apps: OK opens an app; hold OK to add it to the dock or remove it.
- Settings → Clean up TV: connects to the TV's own ADB service (127.0.0.1:5555), so no computer is needed.
  - Make ClearFrame the Home screen: turns off the TV's own home screen (a known launcher only), checks Home now opens ClearFrame, and turns it back on if not.
  - Lists apps with TV Detox's verdicts (safe / review / keep; apps the TV needs are hidden). OK selects; "Turn off selected" asks twice. Apps are turned off, never uninstalled.
  - Turn everything back on: undoes all of it from the list saved on the TV (`/data/local/tmp/clearframe-disabled.txt`).
- Settings → TV settings.

Not yet: reordering the dock.

## Install on the TV (no computer)

1. On the TV: Settings → Device Preferences → About → press OK on Build 7 times. Then Developer options → turn on ADB debugging.
2. Install Downloader (by AFTVnews) from the Play Store, allow it to install unknown apps, and open
   `is.gd/clearframetv`. It redirects to
   `https://github.com/subhamayalahiri-google/ClearFrameTV/releases/download/latest/clearframe.apk`, which always serves the newest build.
3. Open ClearFrame from the TV's apps, go Up → Settings → Clean up TV. Accept "Allow USB debugging?" (tick Always allow).
4. Choose Make ClearFrame the Home screen and press OK twice. Then turn off apps a few at a time, checking the TV still works.

If something goes wrong: Clean up TV → Turn everything back on, or TV Settings → Apps → the app → Enable.

## Build

GitHub Actions builds `clearframe.apk` on every push (see the run's `clearframe-apk` artifact). It also installs the APK on an Android TV 8.0 emulator and saves screenshots to the `ci-screenshots` branch.

Locally: Java 17, Android SDK `platforms;android-34` and `build-tools;34.0.0`, then `sh build.sh`.

Releases are signed with one permanent key, so each update installs over the last one. The key lives only in two repository secrets: `KEYSTORE_B64` (the base64-encoded PKCS12 keystore) and `KEYSTORE_PASS`. The workflow refuses to publish an APK whose certificate SHA-256 differs from `RELEASE_CERT_SHA256` in `.github/workflows/build.yml`. Keep an offline copy of the keystore: if it is lost, the next build can only be installed after uninstalling ClearFrame.

## Credits

Design, wallpapers and icons from CleanFrame by Nathan Clark (MIT). Build approach and clean-up rules from [TV Detox](https://github.com/Ansub/tv-detox) by Ansub Khan (MIT). Full list in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
