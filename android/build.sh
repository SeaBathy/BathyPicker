#!/usr/bin/env bash
# Builds BathyPicker.apk with only the Android SDK command-line tools (no Gradle).
#   bash build.sh            build and sign
# Needs: JDK 17+, Android SDK build-tools 36.1.0 and platform android-36, Python 3.
# The signing key is made the first time in keystore/ (kept out of git). Keep it: updates
# to the app must be signed with the same key.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
EXE=""; BAT=""; SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat"; SEP=";";; esac

KS="keystore/bathypicker.jks"
if [ ! -f "$KS" ]; then
    mkdir -p keystore
    python -I -c "import secrets; print(secrets.token_urlsafe(24))" > keystore/password.txt
    keytool -genkeypair -keystore "$KS" -storepass "$(cat keystore/password.txt)" -alias bathypicker \
        -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=BathyPicker, O=SeaBathy" >/dev/null
    echo "Made a new signing key in $KS. Back it up."
fi
KSPASS="$(cat keystore/password.txt)"

python -I tools/make_page.py

rm -rf build/apk && mkdir -p build/apk/res build/apk/gen build/apk/classes build/apk/dex

"$BT/aapt2$EXE" compile --dir res -o build/apk/res/compiled.zip
"$BT/aapt2$EXE" link -o build/apk/unsigned.apk -I "$JAR" \
    --manifest AndroidManifest.xml --java build/apk/gen -A assets \
    --min-sdk-version 24 --target-sdk-version 35 \
    build/apk/res/compiled.zip

javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -Xlint:deprecation \
    -bootclasspath "$JAR" -classpath "$JAR$SEP$BT/core-lambda-stubs.jar" -d build/apk/classes \
    $(find src build/apk/gen -name '*.java')

"$BT/d8$BAT" --release --min-api 24 --lib "$JAR" --output build/apk/dex \
    $(find build/apk/classes -name '*.class')

cp build/apk/unsigned.apk build/apk/with-dex.apk
python -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" \
    build/apk/with-dex.apk build/apk/dex/classes.dex
"$BT/zipalign$EXE" -f -p 4 build/apk/with-dex.apk build/apk/aligned.apk
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass "pass:$KSPASS" --out BathyPicker.apk build/apk/aligned.apk
"$BT/apksigner$BAT" verify BathyPicker.apk
rm -f BathyPicker.apk.idsig
echo "Built $(pwd)/BathyPicker.apk ($(du -k BathyPicker.apk | cut -f1) KB)"
