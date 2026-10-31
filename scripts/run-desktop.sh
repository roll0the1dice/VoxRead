#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

if [ ! -f "$DIR/desktop-app/build/install/desktop-app/bin/desktop-app" ]; then
    ./gradlew :desktop-app:installDist
fi

exec "$DIR/desktop-app/build/install/desktop-app/bin/desktop-app" "$@"
