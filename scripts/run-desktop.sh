#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

# Auto-detect Java 21 from Gradle JDKs cache if system Java is older
if [ -z "$JAVA_HOME" ] || [ "$("$JAVA_HOME/bin/java" -version 2>&1 | grep -o 'version "[0-9]*' | cut -d'"' -f2)" -lt 21 ] 2>/dev/null; then
    for jdk in "$HOME"/.gradle/jdks/*21*; do
        if [ -d "$jdk" ] && [ -x "$jdk/bin/java" ]; then
            export JAVA_HOME="$jdk"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done
fi

exec ./gradlew :desktop-app:run </dev/null
