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
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DBUILD_SHARED_LIBS=OFF \
  -DBUILD_CXXLIBS=OFF \
  -DBUILD_PROGRAMS=OFF \
  -DBUILD_EXAMPLES=OFF \
  -DBUILD_TESTING=OFF \
  -DBUILD_DOCS=OFF \
  -DINSTALL_MANPAGES=OFF \
  -DWITH_OGG=OFF

# Build only the library target. Building "all" also builds a benchmark tool that fails to link on iOS.
cmake --build build --config Release --target FLAC -j "$(sysctl -n hw.ncpu)"

LIB="$(find build -name libFLAC.a | head -n 1)"
test -n "$LIB" || { echo "libFLAC.a was not produced" >&2; exit 1; }

mkdir -p "$OUT/lib" "$OUT/include"
cp "$LIB" "$OUT/lib/libFLAC.a"
cp -R "flac-${VER}/include/FLAC" "$OUT/include/"

test -f "$OUT/include/FLAC/stream_decoder.h"
lipo -info "$OUT/lib/libFLAC.a" || true
echo "libFLAC installed"
