#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# 1. Locate Java 21 (with jlink)
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jlink" ] || [ "$("$JAVA_HOME/bin/java" -version 2>&1 | grep -o 'version "[0-9]*' | cut -d'"' -f2)" -lt 21 ] 2>/dev/null; then
    for jdk in "$HOME"/.gradle/jdks/*21*; do
        if [ -d "$jdk" ] && [ -x "$jdk/bin/jlink" ]; then
            export JAVA_HOME="$jdk"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jlink" ]; then
    echo "Error: JDK 21 with jlink is required." >&2
    exit 1
fi

echo "==> Using JAVA_HOME: $JAVA_HOME"

# 2. Build fresh distribution JARs via Gradle
echo "==> Building :desktop-app:copyRuntimeLibs..."
./gradlew :desktop-app:copyRuntimeLibs --console=plain

# 3. Prepare AppDir structure
BUILD_DIR="$ROOT_DIR/desktop-app/build/appimage"
APPDIR="$BUILD_DIR/VoxRead.AppDir"
rm -rf "$APPDIR"
mkdir -p \
    "$APPDIR/usr/lib/app" \
    "$APPDIR/usr/lib/javafx" \
    "$APPDIR/usr/share/icons/hicolor/256x256/apps" \
    "$APPDIR/usr/share/applications"

echo "==> Copying application & JavaFX JARs..."
cp -a "$ROOT_DIR/desktop-app/build/app-libs/." "$APPDIR/usr/lib/app/"
mv "$APPDIR/usr/lib/app"/javafx-*.jar "$APPDIR/usr/lib/javafx/"

# 4. Create embedded slim JRE 21 using jlink
echo "==> Creating self-contained Java 21 runtime via jlink..."
"$JAVA_HOME/bin/jlink" \
    --no-header-files \
    --no-man-pages \
    --compress=zip-6 \
    --strip-debug \
    --add-modules java.se,jdk.crypto.ec,jdk.unsupported,jdk.unsupported.desktop,jdk.jsobject,jdk.xml.dom,jdk.charsets,jdk.localedata,jdk.zipfs \
    --include-locales=en,zh \
    --output "$APPDIR/usr/lib/jre"

# 5. Create AppRun entry point
cat > "$APPDIR/AppRun" << 'EOF'
#!/usr/bin/env bash
set -e

HERE="$(dirname "$(readlink -f "${0}")")"
export JAVA_HOME="$HERE/usr/lib/jre"
export PATH="$JAVA_HOME/bin:$PATH"
APP_LIB="$HERE/usr/lib/app"
JAVAFX_LIB="$HERE/usr/lib/javafx"

exec "$JAVA_HOME/bin/java" \
    --module-path "$JAVAFX_LIB" \
    --add-modules javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing \
    -Dawt.useSystemAAFontSettings=on \
    -Dswing.aatext=true \
    -classpath "$APP_LIB/*" \
    com.roll0the1dice.voxread.desktop.VoxReadAppKt \
    "$@"
EOF
chmod +x "$APPDIR/AppRun"

# 6. Desktop entry & icons
cat > "$APPDIR/voxread.desktop" << 'EOF'
[Desktop Entry]
Type=Application
Name=VoxRead
GenericName=现代化电子书阅读器
Comment=跨平台 Compose Desktop 现代化数字阅读与 Edge TTS 语音朗读
Exec=AppRun
Icon=voxread
Terminal=false
Categories=Office;Viewer;
StartupWMClass=com-roll0the1dice-voxread-desktop-VoxReadAppKt
EOF
cp "$APPDIR/voxread.desktop" "$APPDIR/usr/share/applications/voxread.desktop"

ICON_SRC="$ROOT_DIR/desktop-app/src/main/resources/voxread.png"
if [ ! -f "$ICON_SRC" ] && [ -f "$HOME/.local/share/icons/voxread.png" ]; then
    ICON_SRC="$HOME/.local/share/icons/voxread.png"
fi
cp "$ICON_SRC" "$APPDIR/voxread.png"
cp "$ICON_SRC" "$APPDIR/usr/share/icons/hicolor/256x256/apps/voxread.png"
ln -sf voxread.png "$APPDIR/.DirIcon"

# 7. Ensure appimagetool is available
APPIMAGETOOL=""
if command -v appimagetool >/dev/null 2>&1; then
    APPIMAGETOOL="$(command -v appimagetool)"
elif [ -x "$HOME/.local/bin/appimagetool" ]; then
    APPIMAGETOOL="$HOME/.local/bin/appimagetool"
else
    echo "==> Downloading appimagetool..."
    mkdir -p "$HOME/.local/bin"
    curl -fL -o "$HOME/.local/bin/appimagetool" \
        "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
    chmod +x "$HOME/.local/bin/appimagetool"
    APPIMAGETOOL="$HOME/.local/bin/appimagetool"
fi

# 8. Package into single-file AppImage (atomic replace to avoid ETXTBSY if AppImage is running)
OUTPUT_APPIMAGE="$ROOT_DIR/VoxRead-x86_64.AppImage"
TMP_APPIMAGE="$BUILD_DIR/VoxRead-x86_64.AppImage.tmp"
rm -f "$TMP_APPIMAGE"
echo "==> Packaging $OUTPUT_APPIMAGE..."
ARCH=x86_64 "$APPIMAGETOOL" --no-appstream "$APPDIR" "$TMP_APPIMAGE"
chmod +x "$TMP_APPIMAGE"
mv -f "$TMP_APPIMAGE" "$OUTPUT_APPIMAGE"

echo "==> Done! Single-file AppImage generated at:"
ls -lh "$OUTPUT_APPIMAGE"
