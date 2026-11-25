#!/bin/bash
# LauncherBlur build (no gradle): javac -> d8 -> aapt -> zipalign -> apksigner
cd /root/blurwork
SDK=/root/fullaod/sdk
JAR=$SDK/android.jar
OUT=out
KS=/root/fullaod/build/fullaod.jks

rm -rf $OUT/classes $OUT/dex
rm -f $OUT/app.unsigned.apk $OUT/app.aligned.apk $OUT/LauncherBlur-v1.0.apk
mkdir -p $OUT/classes $OUT/dex $OUT/stubs

javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $JAR -d $OUT/stubs $(find stubs -name '*.java') || exit 10
echo STUBS_OK

javac -source 11 -target 11 -encoding UTF-8 -nowarn -cp $JAR:$OUT/stubs -d $OUT/classes $(find src -name '*.java') || exit 11
echo JAVAC_OK

java -cp $SDK/r8.jar com.android.tools.r8.D8 --release --min-api 34 --lib $JAR --output $OUT/dex $(find $OUT/classes -name '*.class') || exit 12
echo D8_OK
ls -l $OUT/dex

aapt package -f -M AndroidManifest.xml -I $JAR -A assets -F $OUT/app.unsigned.apk || exit 13
echo AAPT_OK

cd $OUT/dex && aapt add ../app.unsigned.apk classes.dex || exit 14
cd /root/blurwork

zipalign -f 4 $OUT/app.unsigned.apk $OUT/app.aligned.apk || exit 15
apksigner sign --ks $KS --ks-key-alias fullaod --ks-pass pass:fullaod --key-pass pass:fullaod \
    --out $OUT/LauncherBlur-v1.0.apk $OUT/app.aligned.apk || exit 16

ls -l $OUT/LauncherBlur-v1.0.apk
echo BUILD_OK
