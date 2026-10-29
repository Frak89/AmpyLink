#!/usr/bin/env bash
# Builds AmpyLink.apk without Gradle: aapt2 + javac + d8 (or dx) + apksigner.
# Usage: SDK_JAR=android.jar BT=build-tools-dir ./build-apk.sh
#   or   SDK_JAR=… AAPT2=… DEXER="java -jar dx.jar" APKSIGNER="java -jar apksigner.jar" LAMBDA_STUBS=… ./build-apk.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${SDK_JAR:?path to android.jar (API 34)}"
AAPT2="${AAPT2:-$BT/aapt2}"
LAMBDA_STUBS="${LAMBDA_STUBS:-${BT:-}/core-lambda-stubs.jar}"
APKSIGNER="${APKSIGNER:-java -jar ${BT:-}/lib/apksigner.jar}"
OUT="$HERE/build"; rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex}
SRC="$HERE/app/src/main"
sed 's|<manifest |<manifest package="com.ampylink.app" |' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$SDK_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 30 --target-sdk-version 34 --version-code 2 --version-name 1.0.1 "$OUT/res/res.zip"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK_JAR:$LAMBDA_STUBS" -d "$OUT/classes" \
  $(find "$SRC/java" "$OUT/gen" -name '*.java')
python3 -I "$HERE/tools/strip_methodparams.py" "$OUT/classes"
# Android has no LambdaMetafactory: without D8, desugar lambdas into plain classes (needs ASM_JAR)
if [ -n "${DEXER:-}" ]; then
  : "${ASM_JAR:?path to asm-7+.jar for lambda desugaring}"
  mkdir -p "$OUT/tools" && javac -nowarn -cp "$ASM_JAR" -d "$OUT/tools" "$HERE/tools/Desugar.java"
  java -cp "$ASM_JAR:$OUT/tools" Desugar "$OUT/classes"
  ! (cd "$OUT/classes" && for c in $(find . -name '*.class'); do javap -c -p "$c"; done) | grep -q invokedynamic || { echo "invokedynamic left after desugar"; exit 1; }
fi
if [ -n "${DEXER:-}" ]; then
  $DEXER --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
else
  java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$SDK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
fi
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
KS="${KEYSTORE:-$HERE/ampylink-debug.jks}"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass ampylink -keypass ampylink -alias ampylink \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AmpyLink, O=AmpyLink" >/dev/null 2>&1
$APKSIGNER sign --ks "$KS" --ks-pass pass:ampylink --key-pass pass:ampylink --ks-key-alias ampylink \
  --out "$HERE/AmpyLink.apk" "$OUT/unsigned.apk"
$APKSIGNER verify "$HERE/AmpyLink.apk" && echo "OK -> $HERE/AmpyLink.apk"
