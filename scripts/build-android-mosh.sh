#!/bin/bash
# 构建 Android 版 mosh 原生组件：mosh-client / PTY JNI 桥 / libc++_shared
#
# 背景：Android 端此前把 NDK 交叉编译的 mosh 1.4.0 预编译 .so 直接提交进
# jniLibs，构建命令没有保留。本脚本按当年 /tmp/mosh-build 的实际构建命令
# （config.log / configdata.pm 还原）把整条链路固化：openssl-3.0.13 +
# ncurses-6.4 + protobuf-3.21.12 + mosh-1.4.0，NDK 交叉编译产出：
#
#   composeApp/src/androidMain/jniLibs/<abi>/
#     libmoshclient.so   ← mosh-client PIE 可执行，改名 .so 以便打进 APK
#     libmoshpty.so      ← moshpty.c 的 JNI PTY 桥
#     libc++_shared.so   ← NDK 自带的 C++ 运行库
#
# 依赖：
#   - Android NDK（默认 ~/Library/Android/sdk/ndk；可用 ANDROID_NDK_HOME 覆盖）
#   - macOS / Linux + curl + make + 宿主 C/C++ 工具链（编译宿主 protoc 用）
#
# 源码：优先复用 $MOSH_SRC_BASE（默认 /tmp/mosh-build）下的 tarball，缺失则
# 下载固定版本并校验 SHA-256（protobuf 暂无校验，见 SHA256 表）。全部编译在
# 临时目录进行，脚本可重复执行。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_BASE="$ROOT/composeApp/src/androidMain/jniLibs"
SRC_BASE="${MOSH_SRC_BASE:-/tmp/mosh-build}"
API=26
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

# ---------- 版本（与当年 /tmp/mosh-build 的构建一致） ----------
OPENSSL_VER=3.0.13
NCURSES_VER=6.4
PROTOBUF_VER=3.21.12
MOSH_VER=1.4.0

fetch_source() { # name outfile url sha256
  local name="$1" out="$2" url="$3" sha="$4"
  if [ -f "$out" ]; then
    echo "  复用 $out"
  else
    echo "  下载 $name ($url)"
    mkdir -p "$(dirname "$out")"
    curl -fsSL --retry 3 -o "$out" "$url"
  fi
  if [ -n "$sha" ]; then
    if command -v shasum >/dev/null 2>&1; then
      SHA_CHECK="shasum -a 256"
    else
      SHA_CHECK="sha256sum"
    fi
    echo "$sha  $out" | $SHA_CHECK -c - >/dev/null 2>&1 \
      || { echo "SHA-256 校验失败: $out" >&2; exit 1; }
  fi
}

show_log_on_error() { # logfile
  echo "构建失败，日志: $1" >&2
  tail -40 "$1" >&2
  exit 1
}

mkdir -p "$SRC_BASE"

fetch_source "openssl" "$SRC_BASE/openssl.tar.gz" \
  "https://www.openssl.org/source/openssl-$OPENSSL_VER.tar.gz" \
  "88525753f79d3bec27d2fa7c66aa0b92b3aa9498dafd93d7cfa4b3780cdae313"
fetch_source "ncurses" "$SRC_BASE/ncurses.tar.gz" \
  "https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VER.tar.gz" \
  "6931283d9ac87c5073f30b6290c4c75f21632bb4fc3603ac8100812bed248159"
fetch_source "protobuf" "$SRC_BASE/protobuf.tar.gz" \
  "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VER/protobuf-cpp-$PROTOBUF_VER.tar.gz" \
  ""
fetch_source "mosh" "$SRC_BASE/mosh-$MOSH_VER.tar.gz" \
  "https://github.com/mobile-shell/mosh/releases/download/mosh-$MOSH_VER/mosh-$MOSH_VER.tar.gz" \
  "872e4b134e5df29c8933dff12350785054d2fd2839b5ae6b5587b14db1465ddd"

# ---------- NDK 探测 ----------
HOST_TAG=""
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) echo "不支持的系统: $(uname -s)" >&2; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
if [ -z "$NDK" ]; then
  # 常见 SDK 位置：macOS 默认 / Android SDK 环境变量（本地与 GitHub Actions）
  for sdk in "$HOME/Library/Android/sdk" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$sdk" ] || continue
    # 当年构建用的是 26.1.10909125（OpenSSL 3.0.13 对 NDK r27 兼容性差），优先选它
    for v in 26.1.10909125 27.1.12297006; do
      if [ -d "$sdk/ndk/$v" ]; then NDK="$sdk/ndk/$v"; break 2; fi
    done
    if [ -z "$NDK" ]; then
      for cand in "$sdk/ndk"/*; do
        [ -d "$cand" ] && { NDK="$cand"; break; }
      done
    fi
    [ -n "$NDK" ] && break
  done
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "找不到 NDK，请设置 ANDROID_NDK_HOME（如 ~/Library/Android/sdk/ndk/26.1.10909125）" >&2
  exit 1
fi
NDKBIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -x "$NDKBIN/aarch64-linux-android${API}-clang" ] \
  || { echo "NDK 缺少 API $API 工具链: $NDKBIN" >&2; exit 1; }
echo "== NDK: $NDK =="

# ---------- 宿主 protoc（编译 libprotobuf 需要） ----------
PROTOC_HOST="${MOSH_PROTOC_HOST:-$SRC_BASE/host-protobuf/bin/protoc}"
if [ ! -x "$PROTOC_HOST" ]; then
  echo "== 编译宿主 protoc =="
  WORK="$(mktemp -d /tmp/mosh-android-build-XXXXXX)"
  trap 'rm -rf "$WORK"' EXIT
  tar xzf "$SRC_BASE/protobuf.tar.gz" -C "$WORK"
  (
    cd "$WORK/protobuf-$PROTOBUF_VER"
    ./autogen.sh >"$WORK/host-protobuf.log" 2>&1 \
      || show_log_on_error "$WORK/host-protobuf.log"
    ./configure --prefix="$WORK/host-protobuf" >"$WORK/host-protobuf.log" 2>&1 \
      || show_log_on_error "$WORK/host-protobuf.log"
    make -j"$JOBS" >>"$WORK/host-protobuf.log" 2>&1 \
      || show_log_on_error "$WORK/host-protobuf.log"
    make install >>"$WORK/host-protobuf.log" 2>&1 \
      || show_log_on_error "$WORK/host-protobuf.log"
  )
  PROTOC_HOST="$WORK/host-protobuf/bin/protoc"
  trap - EXIT
else
  echo "== 复用宿主 protoc: $PROTOC_HOST =="
fi
"$PROTOC_HOST" --version

mkdir -p "$OUT_BASE"

build_abi() { # abi triple
  local abi="$1" triple="$2"
  echo "== $abi ($triple) =="
  local work
  work="$(mktemp -d /tmp/mosh-android-$abi-XXXXXX)"
  trap 'rm -rf "$work"' RETURN
  local prefix="$work/prefix"
  local cc="$NDKBIN/$triple$API-clang"
  local cxx="$NDKBIN/$triple$API-clang++"
  local os_target
  case "$abi" in
    arm64-v8a) os_target="arm64" ;;
    x86_64)    os_target="x86_64" ;;
  esac
  # 宿主 ar/ranlib（Apple）对 ELF 对象生成的归档 ld.lld 读不了，必须用 NDK 的
  # llvm-ar/llvm-ranlib，configure 时写入 Makefile 保证 make 阶段一致
  local ar="$NDKBIN/llvm-ar"
  local ranlib="$NDKBIN/llvm-ranlib"

  # ---------- OpenSSL（静态） ----------
  tar xzf "$SRC_BASE/openssl.tar.gz" -C "$work"
  (
    cd "$work/openssl-$OPENSSL_VER"
    export PATH="$NDKBIN:$PATH" ANDROID_NDK_ROOT="$NDK"
      ./Configure "android-$os_target" -D__ANDROID_API__=$API \
        --prefix="$prefix" --openssldir="$prefix/ssl" \
        no-shared no-tests no-asm >"$work/openssl.log" 2>&1 \
      || show_log_on_error "$work/openssl.log"
    make -j"$JOBS" >>"$work/openssl.log" 2>&1 \
      || show_log_on_error "$work/openssl.log"
    make install_sw >>"$work/openssl.log" 2>&1 \
      || show_log_on_error "$work/openssl.log"
  )

  # ---------- ncurses（静态，当年 configure 参数） ----------
  tar xzf "$SRC_BASE/ncurses.tar.gz" -C "$work"
  (
    cd "$work/ncurses-$NCURSES_VER"
    ./configure --host="$triple" --prefix="$prefix" \
      --without-shared --enable-static --without-cxx --without-ada \
      --without-manpages --without-tests --without-debug --enable-pc-files \
      CC="$cc" CFLAGS="-O2" CPPFLAGS="-D__ANDROID_API__=$API" \
      AR="$ar" RANLIB="$ranlib" \
      >"$work/ncurses.log" 2>&1 || show_log_on_error "$work/ncurses.log"
    # 只编库、不编 progs/tests（tabs/toe 等工具需要 libtinfo，交叉编译下会链接失败；
    # 本工程只需要 libncurses.a，tinfo 符号已并入其中）
    make -j"$JOBS" libs >>"$work/ncurses.log" 2>&1 \
      || show_log_on_error "$work/ncurses.log"
    make install.libs >>"$work/ncurses.log" 2>&1 \
      || show_log_on_error "$work/ncurses.log"
    # install.libs 不装 .pc；把生成的 ncurses.pc/tinfo.pc 补进 prefix，
    # 否则 mosh 的 pkg-config 会漏到 Homebrew 的 ncurses
    mkdir -p "$prefix/lib/pkgconfig"
    find "$work/ncurses-$NCURSES_VER" -maxdepth 2 -name '*.pc' \
      -exec cp {} "$prefix/lib/pkgconfig/" \;
  )

  # ---------- protobuf（静态，交叉编译） ----------
  tar xzf "$SRC_BASE/protobuf.tar.gz" -C "$work"
  (
    cd "$work/protobuf-$PROTOBUF_VER"
    ./autogen.sh >"$work/protobuf.log" 2>&1 \
      || show_log_on_error "$work/protobuf.log"
    ./configure --host="$triple" --prefix="$prefix" \
      --with-protoc="$PROTOC_HOST" --disable-shared --enable-static \
      CC="$cc" CXX="$cxx" CXXFLAGS="-std=c++17 -O2" \
      AR="$ar" RANLIB="$ranlib" \
      >"$work/protobuf.log" 2>&1 || show_log_on_error "$work/protobuf.log"
    # 只编库不编 protoc 可执行：目标机上 protoc 链接在 make -j 下会抢跑
    # （libprotobuf.a 尚未写完就链接），且 mosh 只需要 libprotobuf.a
    make -j"$JOBS" -C src libprotobuf.la >>"$work/protobuf.log" 2>&1 \
      || show_log_on_error "$work/protobuf.log"
    make -C src install-libLTLIBRARIES install-nobase_includeHEADERS >>"$work/protobuf.log" 2>&1 \
      || show_log_on_error "$work/protobuf.log"
    make install-pkgconfigDATA >>"$work/protobuf.log" 2>&1 \
      || show_log_on_error "$work/protobuf.log"
  )

  # ---------- mosh（当年 config.log 还原） ----------
  tar xzf "$SRC_BASE/mosh-$MOSH_VER.tar.gz" -C "$work"
  (
    cd "$work/mosh-$MOSH_VER"
    # release tarball 不带 VERSION 文件，而 src/include 的 version.h 规则依赖它
    printf 'mosh %s\n' "$MOSH_VER" > VERSION
    # mosh 的 configure 会从 PATH 找 protoc 做版本一致性检查，必须指向我们的
    # 宿主 protoc（3.21.12），否则会捡到系统装的新版 protobuf
    export PATH="$(dirname "$PROTOC_HOST"):$PATH"
    # 只认本 prefix 的 pkg-config 文件，防止漏到 /opt/homebrew 的 protobuf/abseil/ncurses
    export PKG_CONFIG_PATH="$prefix/lib/pkgconfig"
    export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
    ./configure --host="$triple" --prefix="$prefix" \
      --with-crypto-library=openssl \
      CPPFLAGS="-I$prefix/include -I$prefix/include/ncurses -D__ANDROID_API__=$API" \
      LDFLAGS="-L$prefix/lib" CXXFLAGS="-std=c++17" \
      LIBS="-ldl -lz -pthread -llog" \
      CC="$cc" CXX="$cxx" AR="$ar" RANLIB="$ranlib" \
      PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
      >"$work/mosh.log" 2>&1 || show_log_on_error "$work/mosh.log"
    # 按 SUBDIRS 顺序先逐个编库（生成 version.h/.pb.h 与各 libmosh*.a），
    # 最后只链 mosh-client——不编 mosh-server（Android 端不需要，且其链接在
    # 顶层 make -j 下会因库未就绪而失败）
    for dir in include protobufs util crypto terminal network statesync; do
      make -j"$JOBS" -C "src/$dir" >>"$work/mosh.log" 2>&1 \
        || show_log_on_error "$work/mosh.log"
    done
    make -j"$JOBS" -C src/frontend mosh-client >>"$work/mosh.log" 2>&1 \
      || show_log_on_error "$work/mosh.log"
  )

  # ---------- 产出到 jniLibs ----------
  mkdir -p "$OUT_BASE/$abi"
  install -m 755 "$work/mosh-$MOSH_VER/src/frontend/mosh-client" \
    "$OUT_BASE/$abi/libmoshclient.so"
  "$cc" -shared -fPIC -O2 -o "$OUT_BASE/$abi/libmoshpty.so" \
    "$ROOT/composeApp/src/androidMain/cpp/moshpty.c"
  install -m 644 "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/$triple/libc++_shared.so" \
    "$OUT_BASE/$abi/libc++_shared.so"
  echo "  完成: $OUT_BASE/$abi"
}

build_abi arm64-v8a aarch64-linux-android
build_abi x86_64 x86_64-linux-android

echo
echo "== 全部完成 =="
find "$OUT_BASE" -name '*.so' -exec ls -l {} \;
echo
echo "提示：这些 .so 已 gitignore（由本脚本生成）。CI/本地构建前先执行本脚本，"
echo "或把构建接入 Gradle 任务（见 README 讨论）。"
