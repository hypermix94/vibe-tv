#!/usr/bin/env bash
# Build vibe-tv and install it on an Android TV over the network, then make it the screensaver.
#   TV_HOST=<tv ip> ./install.sh
# Needs JDK 17 and the Android SDK (platform 34, build-tools 34, platform-tools for adb), and network debugging
# switched on on the TV. The first connection asks you to allow this computer on the TV screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || ls -d ~/Library/Java-local/jdk-17*/Contents/Home 2>/dev/null | head -n 1)}"
export JAVA_HOME
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=io.github.hypermix94.vibetv

./gradlew --no-daemon -q assembleRelease
"$ADB" connect "$TV_HOST:5555" >/dev/null
TV() { "$ADB" -s "$TV_HOST:5555" shell "$@"; }
"$ADB" -s "$TV_HOST:5555" install -r app/build/outputs/apk/release/app-release.apk

# The screensaver, starting after 2 minutes without a button press.
TV settings put secure screensaver_enabled 1
TV settings put secure screensaver_activate_on_sleep 1
TV settings put secure screensaver_components "$PKG/.MusicDream"
TV settings put system screen_off_timeout 120000
# What it needs: to read what is playing, to hear the TV's own sound for party mode (no microphone), and to pause
# Android's 24-hour attentive timeout while music plays.
TV cmd notification allow_listener "$PKG/$PKG.NowPlayingListener"
TV pm grant "$PKG" android.permission.RECORD_AUDIO
TV pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
echo "vibe-tv installed and set as the screensaver."
