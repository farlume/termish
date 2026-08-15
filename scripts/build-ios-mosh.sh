#!/bin/bash
# 构建 iOS 版 mosh-client（sim arm64 + device arm64）与 PTY 桥接静态库。
#
# 依赖：
#   /tmp/mosh-build 下的源码包（ncurses-6.4 / protobuf-3.21.12 / mosh-1.4.0）与宿主 protoc
#   iosApp/native 下已有的 iOS OpenSSL（libcrypto.a，由 build-ios-native.sh 产出）
#
# 产物：
#   iosApp/native/bin/mosh-client-sim / mosh-client-device
#   iosApp/native/lib/{sim,device}/libmoshpty.a
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/iosApp/native"
SRC_BASE="${MOSH_SRC_BASE:-/tmp/mosh-build}"
CACHE_BASE="${MOSH_IOS_CACHE:-/tmp/mosh-ios-build}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$CACHE_BASE"

MIN_IOS="13.0"
DEVSYSROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
SIMSDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
JOBS="$(sysctl -n hw.ncpu)"
PROTOC_HOST="$SRC_BASE/protoc-host"

if [ ! -x "$PROTOC_HOST" ]; then
  echo "缺少宿主 protoc: $PROTOC_HOST" >&2
  exit 1
fi

mkdir -p "$WORK/src"
tar xzf "$SRC_BASE/ncurses.tar.gz" -C "$WORK/src"
tar xzf "$SRC_BASE/protobuf.tar.gz" -C "$WORK/src"
tar xzf "$SRC_BASE/mosh-1.4.0.tar.gz" -C "$WORK/src"

echo "== 生成 iOS OpenSSL pkg-config（指向 iosApp/native 现有静态库）=="
gen_openssl_pc() {
  local prefix="$1" libdir="$2"
  mkdir -p "$prefix/lib/pkgconfig"
  cat > "$prefix/lib/pkgconfig/libcrypto.pc" <<EOF
prefix=$libdir/../..
exec_prefix=\${prefix}
libdir=$libdir
includedir=$libdir/../../include

Name: OpenSSL-libcrypto
Description: OpenSSL cryptography library
Version: 3.0.16
Libs: -L\${libdir} -lcrypto
Cflags: -I\${includedir}
EOF
  cat > "$prefix/lib/pkgconfig/libssl.pc" <<EOF
prefix=$libdir/../..
exec_prefix=\${prefix}
libdir=$libdir
includedir=$libdir/../../include

Name: OpenSSL-libssl
Description: OpenSSL Secure Sockets Layer
Version: 3.0.16
Requires.private: libcrypto
Libs: -L\${libdir} -lssl
Cflags: -I\${includedir}
EOF
  cat > "$prefix/lib/pkgconfig/openssl.pc" <<EOF
prefix=$libdir/../..
exec_prefix=\${prefix}
libdir=$libdir
includedir=$libdir/../../include

Name: OpenSSL
Description: Secure Sockets Layer and cryptography libraries and tools
Version: 3.0.16
Requires: libssl libcrypto
EOF
}

build_arch() {
  local name="$1" sdk="$2" minflag="$3" sublib="$4"
  echo "==== 构建 $name ($sdk) ===="
  local PREFIX="$CACHE_BASE/prefix-$name"
  mkdir -p "$PREFIX"
  local NC="$WORK/$name-ncurses-6.4"
  local PB="$WORK/$name-protobuf-3.21.12"
  local MS="$WORK/$name-mosh-1.4.0"
  local CC="$(xcrun -f clang) -arch arm64 -isysroot $sdk $minflag"
  local CXX="$(xcrun -f clang++) -arch arm64 -isysroot $sdk $minflag"
  local OSSL_LIBDIR="$VENDOR/lib/$sublib"
  gen_openssl_pc "$PREFIX" "$OSSL_LIBDIR"

  if [ ! -f "$PREFIX/lib/libncurses.a" ]; then
    echo "-- ncurses --"
    cp -R "$WORK/src/ncurses-6.4" "$NC"
    # iOS SDK 没有 sys/ttydev.h（macOS 有）：跳过 Apple 的 USE_OLD_TTY 分支，
    # 直接使用 termios 的 Bxxx 常量，功能等价。
    sed -i '' 's/defined(__APPLE__))/defined(__APPLE__) \&\& !defined(__ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__))/' \
      "$NC/ncurses/tinfo/lib_baudrate.c"
    (
      cd "$NC"
      ./configure --host=aarch64-apple-darwin --prefix="$PREFIX" \
        --disable-shared --enable-static --without-ada --without-cxx \
        --without-manpages --without-progs --without-tests --without-debug \
        --without-profile --with-normal \
        --enable-pc-files --with-pkg-config-libdir="$PREFIX/lib/pkgconfig" \
        CC="$CC" CXX="$CXX" >/dev/null
      make -j"$JOBS" >/dev/null
      # 只装库与头文件；terminfo 数据（install.data 需运行 tic）我们不使用，
      # 运行时用自己打包的精简 terminfo 树。
      make install.libs install.includes >/dev/null
      # 不同 ncurses 配置头文件安装位置不同（include 根或 include/ncurses），
      # 统一复制到两处，mosh 无论检测到哪个宏都能编译。
      if [ -d "$NC/include" ]; then
        mkdir -p "$PREFIX/include/ncurses"
        cp -R "$NC/include/." "$PREFIX/include/" 2>/dev/null || true
        cp -R "$NC/include/." "$PREFIX/include/ncurses/" 2>/dev/null || true
      fi
    )
    cat > "$PREFIX/lib/pkgconfig/ncurses.pc" <<EOF
prefix=$PREFIX
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: ncurses
Description: ncurses
Version: 6.4
Libs: -L\${libdir} -lncurses
Cflags: -I\${includedir}
EOF
  else
    echo "-- ncurses: 缓存命中 --"
  fi

  if [ ! -f "$PREFIX/lib/libprotobuf.a" ]; then
    echo "-- protobuf --"
    cp -R "$WORK/src/protobuf-3.21.12" "$PB"
    (
      cd "$PB"
      # 发布包不含生成的 configure，需要先用宿主 autoreconf 生成（用 glibtoolize）
      ./autogen.sh >/dev/null
      ./configure --host=aarch64-apple-darwin --prefix="$PREFIX" \
        --disable-shared --enable-static --with-protoc="$PROTOC_HOST" \
        CC="$CC" CXX="$CXX" CXXFLAGS="-std=c++14 -O2" >/dev/null
      make -j"$JOBS" >/dev/null
      make install >/dev/null
    )
  else
    echo "-- protobuf: 缓存命中 --"
  fi

  echo "-- mosh-client --"
  cp -R "$WORK/src/mosh-1.4.0" "$MS"
  # 官方 tarball 不含 VERSION（Android 构建时也是手动补的），version.h 依赖它生成
  printf '1.4.0\n' > "$MS/VERSION"
  # iOS SDK 声明 system() 不可用；此处只是 locale 诊断输出，iOS 下改为空操作
  sed -i '' 's/int unused __attribute((unused)) = system( "locale" );/int unused __attribute((unused)) = 0;\n#if !defined(__ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__)\n    unused = system( "locale" );\n#endif/' \
    "$MS/src/frontend/会话管理"
  (
    cd "$MS"
    # 发布包的 Makefile.in 会触发 automake-1.16 重新生成；用宿主 autoreconf 统一重新生成
    ./autogen.sh >/dev/null
    ./configure --host=aarch64-apple-darwin --prefix="$PREFIX" \
      --with-crypto-library=openssl \
      CC="$CC" CXX="$CXX" \
      CPPFLAGS="-I$PREFIX/include -I$PREFIX/include/ncurses -I$VENDOR/include -I$MS/src/include" \
      CXXFLAGS="-std=c++17 -O2" \
      LDFLAGS="-L$PREFIX/lib -L$OSSL_LIBDIR" \
      LIBS="-lcrypto -lz -pthread" \
      PROTOC="$PROTOC_HOST" \
      PKG_CONFIG=/opt/homebrew/bin/pkg-config \
      PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" >/dev/null
    # 子目录库依赖顺序（automake 顶层递归才会自动处理，这里手动按序构建）
    make -j"$JOBS" -C src/include >/dev/null
    make -j"$JOBS" -C src/crypto >/dev/null
    make -j"$JOBS" -C src/protobufs >/dev/null
    make -j"$JOBS" -C src/util >/dev/null
    make -j"$JOBS" -C src/terminal >/dev/null
    make -j"$JOBS" -C src/network >/dev/null
    make -j"$JOBS" -C src/statesync >/dev/null
    make -j"$JOBS" -C src/frontend mosh-client >/dev/null
  )
  cp "$MS/src/frontend/mosh-client" "$VENDOR/bin/mosh-client-$name"
  cp "$MS/src/frontend/mosh-client" "$ROOT/iosApp/iosApp/mosh-client-$name"
  chmod +x "$ROOT/iosApp/iosApp/mosh-client-$name"
  echo "-- $name mosh-client 完成 --"
  file "$VENDOR/bin/mosh-client-$name"
  otool -L "$VENDOR/bin/mosh-client-$name" | head -8
}

echo "== 编译 PTY 桥接静态库 =="
mkdir -p "$VENDOR/bin"
(
  cd "$ROOT/scripts/ios"
  for entry in "sim $SIMSDK -mios-simulator-version-min=$MIN_IOS" \
               "device $DEVSYSROOT -mios-version-min=$MIN_IOS"; do
    set -- $entry
    name="$1"; sdk="$2"; minflag="$3"
    xcrun --sdk "$sdk" clang -arch arm64 -isysroot "$sdk" "$minflag" \
      -I"$ROOT/scripts/ios" -c moshpty_ios.c -o "$WORK/moshpty-$name.o"
    ar rcs "$VENDOR/lib/$name/libmoshpty.a" "$WORK/moshpty-$name.o"
  done
)

build_arch "sim" "$SIMSDK" "-mios-simulator-version-min=$MIN_IOS" "sim"
build_arch "device" "$DEVSYSROOT" "-mios-version-min=$MIN_IOS" "device"

echo "== 完成 =="
ls -la "$VENDOR/bin"
ls -la "$VENDOR/lib/sim/libmoshpty.a" "$VENDOR/lib/device/libmoshpty.a"
