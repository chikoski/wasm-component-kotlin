#!/usr/bin/env bash
# Runs the ART measurements on the connected device or emulator and writes them to $1 (default
# build/bench-results): cold start in fresh processes, then the Microbenchmark suite.
# Build first: ./gradlew -p samples/android :benchmark:assembleReleaseAndroidTest
set -euo pipefail
cd "$(dirname "$0")"
out="${1:-build/bench-results}"
runs="${COLD_RUNS:-9}"
apk=build/outputs/apk/androidTest/release/benchmark-release-androidTest.apk
pkg=com.example.wasm2kt.bench.test
runner="$pkg/androidx.benchmark.junit4.AndroidBenchmarkRunner"
suppress="EMULATOR,LOW-BATTERY,UNLOCKED"
mkdir -p "$out"

adb install -r -t "$apk"
adb shell getprop ro.build.fingerprint > "$out/device.txt"
adb shell cat /proc/cpuinfo | grep -m1 -i "model name" >> "$out/device.txt" || true
adb shell nproc >> "$out/device.txt" || true

# Cold start: every `am instrument` starts a new process.
adb logcat -c
for i in $(seq 1 "$runs"); do
  for m in arithmetic heavy; do
    adb shell am instrument -w -e class "com.example.wasm2kt.bench.ColdStartProbe#$m" \
      -e androidx.benchmark.suppressErrors "$suppress" "$runner" > /dev/null
  done
done
adb logcat -d -s wasm2kt-bench:I | grep PHASE > "$out/art-cold-start.txt"
# Also in the job log (artifacts may not be downloadable everywhere).
echo "== device"; cat "$out/device.txt"
echo "== cold start"; sed 's/^.*PHASE /PHASE /' "$out/art-cold-start.txt"

# Steady state.
adb shell am instrument -w -r -e class com.example.wasm2kt.bench.CallBenchmark \
  -e androidx.benchmark.suppressErrors "$suppress" "$runner" | tee "$out/art-calls.txt"
adb pull "/sdcard/Android/media/$pkg/" "$out/media" || true
echo "== benchmark data"; find "$out/media" -name '*benchmarkData.json' -exec cat {} \; || true
