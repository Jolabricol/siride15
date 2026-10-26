#!/bin/sh
# SirideAndroid Gradle bootstrap wrapper.
# Uses a locally installed Gradle when available; otherwise downloads the exact
# distribution declared in gradle/wrapper/gradle-wrapper.properties.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=9.1.0
DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
mkdir -p "$CACHE"
ZIP="$CACHE/gradle-${GRADLE_VERSION}-bin.zip"
DIST="$CACHE/gradle-${GRADLE_VERSION}"
if [ ! -x "$DIST/bin/gradle" ]; then
  if [ ! -f "$ZIP" ]; then
    echo "Gradle ${GRADLE_VERSION} absent : téléchargement…" >&2
    command -v curl >/dev/null 2>&1 && curl -fL "$DIST_URL" -o "$ZIP" || wget -O "$ZIP" "$DIST_URL"
  fi
  rm -rf "$CACHE/.extracting"
  mkdir -p "$CACHE/.extracting"
  unzip -q "$ZIP" -d "$CACHE/.extracting"
  rm -rf "$DIST"
  mv "$CACHE/.extracting/gradle-${GRADLE_VERSION}" "$DIST"
  rm -rf "$CACHE/.extracting"
fi
exec "$DIST/bin/gradle" "$@"
