#!/usr/bin/env bash
# Builds the APK for a Meta Horizon Store release channel.
#
# Differs from an ordinary release build in three ways the store insists on,
# none of which should apply to builds for anywhere else:
#
#   * its own application id -- publishing under upstream's would claim another
#     developer's identifier, and collide if they ever ship on this store
#   * minSdk 29, the store's floor; upstream's 25 supports Fire OS
#   * targetSdk 34, the store's ceiling for apps with an immersive activity
#
# A different application id means a separate install: it will not inherit the
# sideloaded app's logins or downloads.
#
# Usage:
#   scripts/build-meta-store.sh com.yourname.plezyquest "Plezy for Quest"
set -euo pipefail

APPLICATION_ID="${1:-}"
if [[ -z "$APPLICATION_ID" ]]; then
  echo "usage: $0 <application-id>   e.g. com.yourname.plezyvr" >&2
  exit 2
fi

if [[ ! -f android/key.properties ]]; then
  echo "android/key.properties is missing: the store ties the app's identity to" >&2
  echo "its signature permanently, so an unsigned or debug-signed build is not" >&2
  echo "recoverable from. Restore the keystore before building." >&2
  exit 1
fi

APP_LABEL="${2:-Plezy for Quest}"

# The store applies immersive-app rules to the launcher activity that would be
# wrong everywhere else, so it gets a manifest generated from the real one.
MANIFEST="$PWD/build/meta/AndroidManifest.xml"
python3 scripts/meta-store-manifest.py android/app/src/main/AndroidManifest.xml "$MANIFEST"

flutter build apk --release \
  --target-platform android-arm64 \
  -Pplezy.applicationId="$APPLICATION_ID" \
  -Pplezy.appLabel="$APP_LABEL" \
  -Pplezy.minSdk=29 \
  -Pplezy.targetSdk=34 \
  -Pplezy.manifest="$MANIFEST" \
  -Pplezy.arm64Only=true

APK="build/app/outputs/flutter-apk/app-release.apk"
echo
echo "built: $APK"
"${ANDROID_HOME:-$HOME/.local/opt/android-sdk}/build-tools/36.1.0/aapt2" dump badging "$APK" \
  | grep -E "^package|targetSdkVersion|sdkVersion:" || true
echo
echo "Upload it to a release channel (ALPHA to start) at"
echo "  https://developers.meta.com/horizon/manage/"
echo "then invite your own Meta account to that channel. It will then install"
echo "from the Library like any other app rather than from Unknown Sources."
