#!/usr/bin/env bash
set -euo pipefail

VER="1.4.3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/composeApp/libs/flac"

if [ -f "$OUT/lib/libFLAC.a" ] && [ -f "$OUT/include/FLAC/stream_decoder.h" ]; then
  echo "libFLAC already built"
  exit 0
fi

command -v cmake >/dev/null 2>&1 || brew install cmake

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

curl -fsSL -o flac.tar.xz "https://github.com/xiph/flac/releases/download/${VER}/flac-${VER}.tar.xz"
tar xf flac.tar.xz

cmake -S "flac-${VER}" -B build \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=iphoneos \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$OUT" \
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_CXXLIBS=OFF \
  -DBUILD_PROGRAMS=OFF \
  -DBUILD_EXAMPLES=OFF \
  -DBUILD_TESTING=OFF \
  -DBUILD_DOCS=OFF \
  -DINSTALL_MANPAGES=OFF \
  -DINSTALL_PKGCONFIG_MODULES=OFF \
  -DINSTALL_CMAKE_CONFIG_MODULE=OFF \
  -DWITH_OGG=OFF

cmake --build build --config Release --target install -j "$(sysctl -n hw.ncpu)"

test -f "$OUT/lib/libFLAC.a"
test -f "$OUT/include/FLAC/stream_decoder.h"
echo "libFLAC installed"
