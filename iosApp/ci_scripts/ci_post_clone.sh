#!/bin/bash
# Xcode Cloud 构建前准备（Xcode Cloud 检出代码后自动执行，本地构建无影响）：
#
#   1. JDK 17——Xcode Cloud 镜像不带 Java，「Build Kotlin Framework」阶段
#      的 gradlew 没有 JDK 直接失败（PhaseScriptExecution failed）。
#      装到 ~/Library/Java/JavaVirtualMachines（免 sudo，java_home 可发现）
#   2. OpenSSL + libssh2 静态库 → iosApp/native/——该目录 git-ignored，
#      干净检出没有；缺失时 Kotlin framework 的 cinterop/链接直接失败
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"

if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  echo "== JDK 17 已存在：$(/usr/libexec/java_home -v 17) =="
else
  echo "== 安装 JDK 17（Adoptium Temurin，免 sudo） =="
  JDK_HOME="$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk"
  mkdir -p "$JDK_HOME"
  # Adoptium API 的 arch 用 aarch64/x64（macOS uname 返回 arm64/x86_64，直接拼 URL 会 404）。
  # 未知架构兜底轮询（Xcode Cloud 只有 ARM/Intel 两种机器）；tar 校验确保拿到有效包而非错误页
  RAW_ARCH="$(uname -m)"
  case "$RAW_ARCH" in
    arm64|aarch64) ARCHES=(aarch64) ;;
    x86_64|x64) ARCHES=(x64) ;;
    *) ARCHES=(aarch64 x64) ;;
  esac
  echo "== uname -m: $RAW_ARCH → 尝试 arch: ${ARCHES[*]} =="
  ok=""
  for arch in "${ARCHES[@]}"; do
    url="https://api.adoptium.net/v3/binary/latest/17/ga/mac/${arch}/jdk/hotspot/normal/eclipse"
    echo "== 下载 Temurin 17 (${arch}) =="
    if curl -fsSL --retry 3 "$url" -o /tmp/temurin17.tar.gz && tar tzf /tmp/temurin17.tar.gz >/dev/null 2>&1; then
      ok="$arch"; break
    fi
    echo "== ${arch} 下载/校验失败，换下一个 =="
  done
  if [ -z "$ok" ]; then
    echo "!! JDK 17 下载失败（uname=$RAW_ARCH，尝试过 ${ARCHES[*]}）"; exit 1
  fi
  echo "== 使用 ${ok} 版 JDK =="
  tar xzf /tmp/temurin17.tar.gz -C "$JDK_HOME" --strip-components=1
  echo "== JDK 17 安装完成：$(/usr/libexec/java_home -v 17) =="
fi

echo "== 构建 iOS 原生依赖（OpenSSL + libssh2 → iosApp/native/） =="
cd "$ROOT"
bash scripts/build-ios-native.sh
echo "== ci_post_clone 完成 =="
