#!/bin/bash
set -euo pipefail

source_dir="$(cd "$(dirname "$0")" && pwd)"
output="$1"
mkdir -p "$(dirname "$output")"
xcrun clang -dynamiclib -fobjc-arc -fblocks -arch arm64 -mmacosx-version-min=12.0 \
    -Wall -Wextra -Werror -Wno-unused-parameter \
    -framework Foundation -framework AppKit -framework MediaPlayer \
    -install_name @rpath/libmelodia_macos.dylib \
    "$source_dir/nowplaying.m" "$source_dir/windowchrome.m" -o "$output"
codesign --force --sign - "$output"
