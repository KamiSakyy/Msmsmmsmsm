#!/bin/bash
set -e

# If system gradle is available, use it directly
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    mkdir -p "$APP_HOME/gradle/wrapper"
    echo "Downloading gradle-wrapper.jar..."
    curl -sSL -o "$WRAPPER_JAR" "https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || true
fi

if [ -f "$WRAPPER_JAR" ]; then
    exec java -jar "$WRAPPER_JAR" "$@"
fi

echo "ERROR: Gradle wrapper jar could not be found or downloaded."
exit 1
