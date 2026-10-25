#!/bin/sh
# Builds out/clearframe.apk without Gradle. Needs Java 17 and the Android SDK
# (platforms;android-34 and build-tools;34.0.0). GitHub Actions runs this for you.
# Adapted from TV Detox's build.sh (github.com/Ansub/tv-detox, MIT).
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] && [ -d "$BT" ] || { echo "Android SDK not found. Set ANDROID_HOME, and install platforms;android-34 and build-tools;34.0.0." >&2; exit 1; }

rm -rf out; mkdir -p out/classes out/dex

"$BT/aapt2" compile --dir app/res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest app/AndroidManifest.xml -A app/assets -R out/res.zip --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34
# shellcheck disable=SC2046 # javac and d8 want one argument per file
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d out/classes $(find app/src -name "*.java")
# shellcheck disable=SC2046
"$BT/d8" --min-api 26 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk; (cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# Updates must be signed with the same key. CI passes a stored key through KEYSTORE and
# KEYSTORE_PASS; without one, a throwaway key is made and updates need an uninstall first.
KS=${KEYSTORE:-key.jks}; PASS=${KEYSTORE_PASS:-clearframe}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias clearframe \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=clearframe" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/clearframe.apk out/aligned.apk
echo "Built out/clearframe.apk"
