#!/bin/bash
# Xcode Cloud 构建前准备（Xcode Cloud 检出代码后自动执行，本地构建无影响）：
#
#   1. JDK 17——Xcode Cloud 镜像不带 Java，「Build Kotlin Framework」阶段
#      的 gradlew 没有 JDK 直接失败（PhaseScriptExecution failed）。
#      装到 ~/Library/Java/JavaVirtualMachines（免 sudo，java_home 可发现）
#   2. OpenSSL + libssh2 静态库 → iosApp/native/——该目录 git-ignored，
#      干净检出没有；缺失时 Kotlin framework 的 cinterop/链接直接失败
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  echo "== JDK 17 已存在：$(/usr/libexec/java_home -v 17) =="
else
  echo "== 安装 JDK 17（Adoptium Temurin，免 sudo） =="
  JDK_HOME="$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk"
  mkdir -p "$JDK_HOME"
  ARCH="$(uname -m)"
  curl -fsSL --retry 3 \
    "https://api.adoptium.net/v3/binary/latest/17/ga/mac/${ARCH}/jdk/hotspot/normal/eclipse" \
    -o /tmp/temurin17.tar.gz
  tar xzf /tmp/temurin17.tar.gz -C "$JDK_HOME" --strip-components=1
  echo "== JDK 17 安装完成：$(/usr/libexec/java_home -v 17) =="
fi

echo "== 构建 iOS 原生依赖（OpenSSL + libssh2 → iosApp/native/） =="
cd "$ROOT"
bash scripts/build-ios-native.sh
echo "== ci_post_clone 完成 =="
