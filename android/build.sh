#!/usr/bin/env bash
# Construit l'appli Bot CoC pour le téléphone : ./build.sh → build/bot-coc.apk
# Sans Gradle. OpenCV (partie arm64 seulement, ~10 Mo) est récupéré au besoin.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
CV=libs/opencv-4.12.0
KS=keystore.jks          # à garder : sans lui, plus de mise à jour possible
PASS=botcoc
OUT=build

if [ ! -f "$CV/classes.jar" ]; then
  python3 -I tools/zip_distant.py https://repo1.maven.org/maven2/org/opencv/opencv/4.12.0/opencv-4.12.0.aar \
    "$CV" classes.jar jni/arm64-v8a/libopencv_java4.so jni/arm64-v8a/libc++_shared.so
fi
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
    -alias botcoc -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Bot CoC" >/dev/null
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets/templates" "$OUT/lib/arm64-v8a"

# modèles du bot du PC (templates/), sans les captures de débogage
cp -r ../templates/. "$OUT/assets/templates/"
(cd "$OUT/assets/templates" && find . -type f -name '*.png' -o -type f -name '*.json' | sort > ../liste.txt)
cp "$CV"/jni/arm64-v8a/*.so "$OUT/lib/arm64-v8a/"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
VC=$(( ( $(date +%s) - 1767225600 ) / 60 ))
"$BT/aapt2" link -o "$OUT/app.u.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --java "$OUT/gen" -A "$OUT/assets" --version-code "$VC" --version-name "1.0.$VC" "$OUT/res.zip"

"$JAVA_HOME/bin/javac" --release 17 -nowarn -encoding UTF-8 -cp "$JAR:$CV/classes.jar" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 33 --lib "$JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') "$CV/classes.jar"

python3 -I - "$OUT/app.u.apk" "$OUT/dex/classes.dex" "$OUT/lib" <<'PY'
import os, sys, zipfile
apk, dex, lib = sys.argv[1:]
with zipfile.ZipFile(apk, "a") as z:
    z.write(dex, "classes.dex", zipfile.ZIP_DEFLATED)
    for abi in os.listdir(lib):
        for so in sorted(os.listdir(os.path.join(lib, abi))):
            z.write(os.path.join(lib, abi, so), f"lib/{abi}/{so}", zipfile.ZIP_STORED)
PY
"$BT/zipalign" -P 16 -f 4 "$OUT/app.u.apk" "$OUT/app.al.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$OUT/bot-coc.apk" "$OUT/app.al.apk"
echo "OK -> $OUT/bot-coc.apk (version $VC)"
