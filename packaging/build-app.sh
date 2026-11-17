#!/bin/bash
# 把 F1 Dashboard 打包成 macOS 应用(.app):双击启动,Dock 里是 F1 图标。
#
#   ./build-app.sh                  → 只打包到 target/dist/
#   ./build-app.sh --install        → 打包并复制到 ~/Applications(启动台可见)
#   ./build-app.sh --desktop        → 再复制一份到 ~/Desktop(桌面启动器)
#   ./build-app.sh --rebuild-icon   → 先重新生成图标再打包
#   (参数可组合,例如 --rebuild-icon --install --desktop)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

MVN="$(command -v mvn || echo "/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn")"
JAVA_HOME_DIR="$(/usr/libexec/java_home -v 26 2>/dev/null || echo "/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home")"
JPACKAGE="$JAVA_HOME_DIR/bin/jpackage"
M2="$HOME/.m2/repository"
APP="F1 Dashboard"
# 注意:jpackage 的 --app-version 不允许以 0 开头,故与 pom 的 0.1.0 分开
APPVER="1.0.0"
JAR="f1-dashboard-0.1.0.jar"

REBUILD_ICON=0
INSTALL=0
DESKTOP=0
for a in "$@"; do
  case "$a" in
    --rebuild-icon) REBUILD_ICON=1 ;;
    --install)      INSTALL=1 ;;
    --desktop)      DESKTOP=1 ;;
    *) echo "✗ 未知参数:$a"; exit 1 ;;
  esac
done

[ -x "$JPACKAGE" ] || { echo "✗ 找不到 jpackage:$JPACKAGE"; exit 1; }

if [ "$REBUILD_ICON" = 1 ] || [ ! -f packaging/F1Dashboard.icns ]; then
  echo "① 生成图标"
  ./packaging/make-icon.sh
fi

echo "② 编译"
"$MVN" -q -o compile

echo "③ 打 jar"
(cd target/classes && jar cf "../$JAR" .)

echo "④ 收集依赖(JavaFX 必须用 -mac-aarch64 分类 jar:无分类的是空壳)"
rm -rf target/package-input && mkdir -p target/package-input
cp "target/$JAR" target/package-input/
for m in base graphics controls; do
  cp "$M2/org/openjfx/javafx-$m/26.0.2/javafx-$m-26.0.2-mac-aarch64.jar" target/package-input/
done
cp "$M2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar" \
   "$M2/com/fasterxml/jackson/core/jackson-core/2.22.2/jackson-core-2.22.2.jar" \
   "$M2/com/fasterxml/jackson/core/jackson-databind/2.22.2/jackson-databind-2.22.2.jar" \
   target/package-input/

echo "⑤ jpackage 打包"
rm -rf target/dist
"$JPACKAGE" --type app-image --name "$APP" --app-version "$APPVER" \
  --input target/package-input --main-jar "$JAR" \
  --main-class f1.app.Main --icon packaging/F1Dashboard.icns \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --dest target/dist

echo
echo "✓ 打包完成:$ROOT/target/dist/$APP.app"

if [ "$INSTALL" = 1 ]; then
  DEST="$HOME/Applications"
  mkdir -p "$DEST"
  rm -rf "$DEST/$APP.app"
  cp -R "target/dist/$APP.app" "$DEST/"
  echo "✓ 已安装到 $DEST/$APP.app ——「启动台 / 应用程序」里可见"
fi

# 桌面放一份完整副本(不是替身 —— 替身会在每次重建后失效)
if [ "$DESKTOP" = 1 ]; then
  DT="$HOME/Desktop"
  rm -rf "$DT/$APP.app"
  ditto "target/dist/$APP.app" "$DT/$APP.app"
  echo "✓ 已复制到 $DT/$APP.app —— 桌面双击即可启动"
fi

if [ "$INSTALL" = 0 ] && [ "$DESKTOP" = 0 ]; then
  echo "  提示:--install 复制到 ~/Applications(启动台可见);--desktop 复制到桌面"
fi
