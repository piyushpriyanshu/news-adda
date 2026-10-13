#!/usr/bin/env bash
# Installs the debug APK on the CI emulator, opens every section against the
# live feed, saves screenshots and checks logcat for crashes.
set -u
PKG=com.newsadda.app.debug
OUT=smoke
mkdir -p "$OUT"
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; }
tap() { python3 .github/scripts/ui_tap.py "$@" >> "$OUT/steps.txt" 2>&1 || echo "MISSING: $1" >> "$OUT/steps.txt"; }

adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c
adb shell am start -W -n $PKG/com.newsadda.app.MainActivity
shot 01_home 30

i=2
for t in "ब्रेकिंग न्यूज़" "नेता ट्रैकर" "राजनीतिक विवाद" "सीट और बाहुबली" "नेताओं के बयान" "जिला समाचार" "सेटिंग्स"; do
  tap "$t"
  shot "$(printf %02d $i)_$(echo "$t" | tr ' ' '_')" 3
  i=$((i+1))
done

# District detail, then home -> open first story's detail sheet.
tap "जिला समाचार"; sleep 2; tap "अमेठी"; shot 10_district_detail 3
adb shell input keyevent 4; sleep 2   # back -> home tab
shot 10b_back_home 2
tap "मूल खबर" --above 220; shot 11_story_detail 3
adb shell input keyevent 4; sleep 1
tap "खोजें"; sleep 2; tap "नेता, दल, जिला"; sleep 1; adb shell input text "yogi"; shot 12_search 3
adb shell input keyevent 4; adb shell input keyevent 4; sleep 1

# Background/foreground cycle: the app must refresh on return without crashing.
adb shell input keyevent 3; sleep 5
adb shell am start -n $PKG/com.newsadda.app.MainActivity; shot 13_resume 8

# Offline: cached stories must still show, with an offline notice.
adb shell svc wifi disable; adb shell svc data disable; sleep 2
tap "अभी अपडेट करें"; shot 14_offline 6
adb shell svc wifi enable; adb shell svc data enable

adb logcat -d > "$OUT/logcat.txt"
grep -E "NewsRepo|Update" "$OUT/logcat.txt" | tail -40 > "$OUT/app_log.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/CRASH.txt"
  echo "crash=yes" > "$OUT/result.txt"
else
  echo "crash=no" > "$OUT/result.txt"
fi
adb shell dumpsys activity processes | grep -c "$PKG" >> "$OUT/result.txt" || true
