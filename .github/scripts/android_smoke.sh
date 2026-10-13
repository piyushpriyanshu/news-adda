#!/usr/bin/env bash
# Installs the debug APK on the CI emulator, opens every section against the
# live feed in English, switches the interface to Hindi, saves screenshots and
# checks logcat for crashes.
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
adb shell input swipe 540 1700 540 700 400; shot 01b_home_scrolled 2
adb shell input swipe 540 700 540 1900 300; sleep 1

i=2
for t in "Breaking" "Leader tracker" "Controversies" "Seats & Bahubalis" "Statements" "Districts"; do
  tap "$t"
  shot "$(printf %02d $i)_$(echo "$t" | tr ' &' '__')" 3
  i=$((i+1))
done
# Still on Districts: open one district, then back (returns to Home).
tap "Amethi"; shot 10_district_detail 3
adb shell input keyevent 4; sleep 2
shot 10b_back_home 2
tap "Settings"; shot 08_Settings 3
adb shell input keyevent 4; sleep 2
tap "Saved"; shot 09_Saved 2
adb shell input keyevent 4; sleep 2
tap "Original" --above 220; shot 11_story_detail 3
adb shell input keyevent 4; sleep 1
tap "Search"; sleep 2; tap "Leader, party, district"; sleep 1; adb shell input text "yogi"; shot 12_search_english_query 3
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1   # hide keyboard, close search
adb shell am start -n $PKG/com.newsadda.app.MainActivity; sleep 2          # in case back left the app

# Dark theme.
adb shell cmd uimode night yes; sleep 3; shot 12b_home_dark 3
adb shell cmd uimode night no; sleep 3

# Switch the interface to Hindi; the news must stay as it is.
tap "Settings"; sleep 2; tap "हिंदी"; shot 13_settings_hindi 3
adb shell input keyevent 4; shot 14_home_hindi 3

# Background/foreground cycle: the app must refresh on return without crashing.
adb shell input keyevent 3; sleep 5
adb shell am start -n $PKG/com.newsadda.app.MainActivity; shot 15_resume 8

# Offline: cached stories must still show, with an offline notice.
adb shell svc wifi disable; adb shell svc data disable; sleep 2
tap "अभी अपडेट करें"; shot 16_offline 6
adb shell svc wifi enable; adb shell svc data enable

adb logcat -d > "$OUT/logcat.txt"
grep -E "NewsRepo|Update|AndroidRuntime" "$OUT/logcat.txt" | grep -v Hotword | tail -40 > "$OUT/app_log.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/CRASH.txt"
  echo "crash=yes" > "$OUT/result.txt"
else
  echo "crash=no" > "$OUT/result.txt"
fi
rm -f "$OUT/logcat.txt"   # large; the relevant lines are in app_log.txt / CRASH.txt
