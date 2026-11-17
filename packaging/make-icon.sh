#!/bin/bash
# 生成 F1 Dashboard 的 .icns 图标。
#
#   ./make-icon.sh              → 用内置方格旗设计矢量重绘(不需要任何素材)
#   ./make-icon.sh 我的图.png    → 用你给的图片生成(建议 1024×1024 正方形、带透明通道)
#
# 换图标就是:把新图片丢进来跑一次,再跑 ./build-app.sh 重新打包。
set -e
cd "$(dirname "$0")"
SRC="$1"
ICONSET="F1Dashboard.iconset"
rm -rf "$ICONSET" && mkdir -p "$ICONSET"

if [ -n "$SRC" ]; then
  [ -f "$SRC" ] || { echo "✗ 找不到图片:$SRC"; exit 1; }
  echo "用图片生成图标:$SRC"
  gen() { sips -z "$1" "$1" "$SRC" --out "$ICONSET/$2" >/dev/null; }
  gen 16   icon_16x16.png;      gen 32   icon_16x16@2x.png
  gen 32   icon_32x32.png;      gen 64   icon_32x32@2x.png
  gen 128  icon_128x128.png;    gen 256  icon_128x128@2x.png
  gen 256  icon_256x256.png;    gen 512  icon_256x256@2x.png
  gen 512  icon_512x512.png;    gen 1024 icon_512x512@2x.png
else
  echo "用内置方格旗设计生成图标"
  java MakeIcon.java "$ICONSET"
fi

iconutil -c icns "$ICONSET" -o F1Dashboard.icns
echo "✓ 已生成 $(pwd)/F1Dashboard.icns"
