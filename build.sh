#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
TC=${TC:-/home/gsl/.cache/fullaod-toolchain}
export JAVA_HOME=${JAVA_HOME:-$TC/jdk}
export PATH=$JAVA_HOME/bin:$TC/build-tools:$PATH
JAR=${ANDROID_JAR:-$TC/android.jar}
D8_JAR=${D8_JAR:-$TC/build-tools/lib/d8.jar}
KS=${KS:-/home/gsl/VScode/FullAOD-handoff-20261122/signing/fullaod.jks}
OUT=out

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/stubs" "$OUT/gen"
rm -f "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk" "$OUT/LauncherBlur.apk" "$OUT/LauncherBlur.apk.idsig"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/stubs" "$OUT/gen"

javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp "$JAR" -d "$OUT/stubs" $(find stubs -name '*.java')
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$JAR" -A assets -F "$OUT/app.unsigned.apk"
javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp "$JAR:$OUT/stubs" -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 34 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && aapt add ../app.unsigned.apk classes.dex)
zipalign -f 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
apksigner sign --ks "$KS" --ks-key-alias fullaod --ks-pass pass:fullaod --key-pass pass:fullaod \
    --out "$OUT/LauncherBlur.apk" "$OUT/app.aligned.apk"
ls -la "$OUT/LauncherBlur.apk"
echo BUILD_OK
