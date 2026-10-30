#!/bin/bash
# 把 Homebrew 安装的 libmpv 连同全部依赖拷进 native/macos，并把依赖路径改写为 @loader_path，
# 使安装包不再依赖本机的 Homebrew。需要先 brew install mpv dylibbundler
set -euo pipefail

OUT_DIR="$1"
BREW_PREFIX="$(brew --prefix)"
SRC="$BREW_PREFIX/lib/libmpv.2.dylib"

[ -f "$SRC" ] || { echo "未找到 $SRC，请先 brew install mpv" >&2; exit 1; }
command -v dylibbundler >/dev/null || { echo "未找到 dylibbundler，请先 brew install dylibbundler" >&2; exit 1; }

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
cp -L "$SRC" "$OUT_DIR/libmpv.dylib"
chmod u+w "$OUT_DIR/libmpv.dylib"
install_name_tool -id "@loader_path/libmpv.dylib" "$OUT_DIR/libmpv.dylib" 2>/dev/null

# -of 覆盖已存在文件，-b 拷贝并改写依赖，-cd 不建子目录
dylibbundler -of -b -cd \
    -x "$OUT_DIR/libmpv.dylib" \
    -d "$OUT_DIR" \
    -p "@loader_path/" \
    -s "$BREW_PREFIX/lib" >/dev/null 2>&1

# dylibbundler 会重复写入 LC_RPATH，新版 dyld 遇到重复项直接拒绝加载；
# 依赖已改为 @loader_path 绝对引用，不需要 rpath，全部删掉
for lib in "$OUT_DIR"/*.dylib; do
    otool -l "$lib" | awk '/cmd LC_RPATH/{getline; getline; print $2}' | while read -r rp; do
        install_name_tool -delete_rpath "$rp" "$lib" 2>/dev/null || true
    done
done

# 改写路径会使原签名失效，Apple Silicon 拒绝加载未签名的库，统一补 ad-hoc 签名
for lib in "$OUT_DIR"/*.dylib; do
    codesign --force --sign - "$lib" 2>/dev/null
done

echo "已打包 $(ls "$OUT_DIR" | wc -l | tr -d ' ') 个动态库到 $OUT_DIR"
