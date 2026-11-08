#!/usr/bin/env bash
# Builds an LGPL-only FFmpeg (decoders only) plus dav1d (BSD, for AV1) as static libraries for
# the app's ABIs. Output: app/ffmpeg/<abi>/{include,lib}. CMake links them into libnvpffmpeg.so.
#
# Needs: Android NDK (NDK env var), curl, make, pkg-config, meson, ninja.
# No --enable-gpl / --enable-nonfree: the result stays LGPL 2.1+.
set -euo pipefail

FFMPEG_VERSION=7.1.5
FFMPEG_SHA256=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
DAV1D_VERSION=1.5.4
DAV1D_SHA256=686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5
API=26
ABIS=${ABIS:-"arm64-v8a armeabi-v7a x86_64"}

AUDIO_DECODERS="aac mp3 ac3 eac3 dca truehd mlp vorbis opus flac alac pcm_mulaw pcm_alaw amrnb amrwb"
VIDEO_DECODERS="h264 hevc vp8 vp9 mpeg4 mpeg2video mpeg1video h263 libdav1d"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$ROOT/build/ffmpeg-work}"
OUT="$ROOT/app/ffmpeg"
: "${NDK:?set NDK to the Android NDK path}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
JOBS=$(nproc 2>/dev/null || echo 4)

mkdir -p "$WORK" && cd "$WORK"
fetch() { # url sha256 file
  [ -f "$3" ] || curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c -
}
fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" $FFMPEG_SHA256 ffmpeg.tar.xz
fetch "https://downloads.videolan.org/pub/videolan/dav1d/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.xz" $DAV1D_SHA256 dav1d.tar.xz
rm -rf "ffmpeg-$FFMPEG_VERSION" "dav1d-$DAV1D_VERSION"
tar xJf ffmpeg.tar.xz
tar xJf dav1d.tar.xz

for ABI in $ABIS; do
  case $ABI in
    arm64-v8a)   TRIPLE=aarch64-linux-android;     FARCH=aarch64; FCPU=armv8-a;  MFAM=aarch64; MCPU=armv8
                 FEXTRA=""; DASM=true ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi;  FARCH=arm;     FCPU=armv7-a;  MFAM=arm;     MCPU=armv7
                 FEXTRA="--enable-neon"; DASM=true ;;
    x86_64)      TRIPLE=x86_64-linux-android;      FARCH=x86_64;  FCPU=x86-64;   MFAM=x86_64;  MCPU=x86_64
                 FEXTRA="--disable-asm"; DASM=false ;;
    *) echo "unknown ABI $ABI"; exit 1 ;;
  esac
  PREFIX="$OUT/$ABI"
  rm -rf "$PREFIX"
  CC="$TC/${TRIPLE}${API}-clang"
  CFLAGS_ABI=""
  [ "$ABI" = armeabi-v7a ] && CFLAGS_ABI="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"

  # dav1d (static).
  cat > "cross-$ABI.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$TC/${TRIPLE}${API}-clang++'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_args = [$(for f in $CFLAGS_ABI; do printf "'%s', " "$f"; done)'-fPIC']

[host_machine]
system = 'android'
cpu_family = '$MFAM'
cpu = '$MCPU'
endian = 'little'
EOF
  rm -rf "dav1d-build-$ABI"
  meson setup "dav1d-build-$ABI" "dav1d-$DAV1D_VERSION" --cross-file "cross-$ABI.txt" \
    --prefix="$PREFIX" --libdir=lib --buildtype=release --default-library=static \
    -Denable_tools=false -Denable_tests=false -Denable_examples=false -Denable_asm=$DASM
  ninja -C "dav1d-build-$ABI" install

  # FFmpeg (static, decoders only).
  DEC=""
  for d in $AUDIO_DECODERS $VIDEO_DECODERS; do DEC="$DEC --enable-decoder=$d"; done
  (
    cd "ffmpeg-$FFMPEG_VERSION"
    make distclean >/dev/null 2>&1 || true
    PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" ./configure \
      --prefix="$PREFIX" \
      --target-os=android --arch=$FARCH --cpu=$FCPU --enable-cross-compile \
      --cc="$CC" --cxx="$TC/${TRIPLE}${API}-clang++" \
      --nm="$TC/llvm-nm" --ar="$TC/llvm-ar" --ranlib="$TC/llvm-ranlib" --strip="$TC/llvm-strip" \
      --pkg-config=pkg-config --pkg-config-flags=--static \
      --extra-cflags="-O3 -fPIC $CFLAGS_ABI" \
      --enable-static --disable-shared --enable-pic \
      --disable-doc --disable-programs --disable-autodetect --disable-everything \
      --disable-avdevice --disable-avformat --disable-avfilter --disable-postproc \
      --enable-avcodec --enable-swresample --enable-swscale \
      --disable-symver --disable-v4l2-m2m --disable-vulkan --disable-mediacodec --disable-jni \
      --enable-libdav1d $FEXTRA $DEC
    make -j"$JOBS"
    make install
  )
  rm -rf "$PREFIX/share" "$PREFIX/lib/pkgconfig"
  ls -la "$PREFIX/lib"
done
echo "FFmpeg $FFMPEG_VERSION + dav1d $DAV1D_VERSION built into $OUT"
