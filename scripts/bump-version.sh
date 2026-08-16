#!/usr/bin/env bash
#
# 一键升级 Termish 版本号：Android / iOS / 桌面（DMG/MSI/DEB）一起升。
#
# 用法:
#   ./scripts/bump-version.sh 0.3.0            # 版本 0.3.0，构建号自动 +1
#   ./scripts/bump-version.sh 0.3.0 42         # 版本 0.3.0，构建号固定 42
#   ./scripts/bump-version.sh --dry-run 0.3.0  # 只预览改动，不写文件
#
# 更新位置:
#   - composeApp/build.gradle.kts
#       Android: versionName / versionCode
#       桌面:    packageVersion（jpackage 要求主版本号 > 0，
#                因此 App 0.Y.Z 会映射为桌面包 1.Y.Z）
#   - iosApp/iosApp.xcodeproj/project.pbxproj
#       MARKETING_VERSION / CURRENT_PROJECT_VERSION（Debug + Release 两处）
#   - iosApp/iosApp/Info.plist
#       CFBundleShortVersionString / CFBundleVersion

set -euo pipefail
cd "$(dirname "$0")/.."

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
  shift
fi

if [ $# -lt 1 ]; then
  echo "用法: $0 [--dry-run] <新版本> [构建号]" >&2
  echo "  例: $0 0.3.0          # 构建号自动 +1" >&2
  echo "      $0 0.3.0 42       # 构建号固定 42" >&2
  exit 1
fi

VERSION="${1#v}"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "版本号格式应为 x.y.z，收到: $VERSION" >&2
  exit 1
fi
# 桌面包版本：jpackage 打 DMG 要求 MAJOR > 0，App 0.Y.Z 映射为 1.Y.Z
if [[ "$VERSION" =~ ^0\.([0-9]+\.[0-9]+)$ ]]; then
  DESKTOP_VERSION="1.${BASH_REMATCH[1]}"
else
  DESKTOP_VERSION="$VERSION"
fi

GRADLE="composeApp/build.gradle.kts"
PBXPROJ="iosApp/iosApp.xcodeproj/project.pbxproj"
INFO_PLIST="iosApp/iosApp/Info.plist"

# 当前 Android versionCode，用于默认构建号 +1
CURRENT_CODE=$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)
if [ -z "$CURRENT_CODE" ]; then
  echo "未能在 $GRADLE 找到 versionCode" >&2
  exit 1
fi
BUILD="${2:-$((CURRENT_CODE + 1))}"
if ! [[ "$BUILD" =~ ^[0-9]+$ ]]; then
  echo "构建号应为正整数，收到: $BUILD" >&2
  exit 1
fi

echo "版本: $VERSION  桌面包版本: $DESKTOP_VERSION  构建号: $BUILD  ($([ "$DRY_RUN" = 1 ] && echo DRY-RUN 仅预览 || echo 写入))"
echo

apply() {
  local label="$1" file="$2" expr="$3"
  local tmp
  tmp=$(mktemp)
  # 先写到临时文件再替换：同一套 sed 表达式兼容 macOS/Linux
  sed "$expr" "$file" > "$tmp"
  if diff -q "$file" "$tmp" > /dev/null; then
    rm -f "$tmp"
    return
  fi
  echo "  [$([ "$DRY_RUN" = 1 ] && echo 预览 || echo 已改)] $label"
  if [ "$DRY_RUN" = 1 ]; then
    diff -u "$file" "$tmp" | tail -n +3 | sed 's/^/    /' || true
    rm -f "$tmp"
  else
    mv "$tmp" "$file"
  fi
}

apply "Android versionName -> $VERSION" "$GRADLE" "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/"
apply "Android versionCode -> $BUILD" "$GRADLE" "s/versionCode = [0-9][0-9]*/versionCode = $BUILD/"
apply "桌面 packageVersion -> $DESKTOP_VERSION" "$GRADLE" "s/packageVersion = \"[^\"]*\"/packageVersion = \"$DESKTOP_VERSION\"/"
apply "iOS MARKETING_VERSION -> $VERSION" "$PBXPROJ" "s/MARKETING_VERSION = [^;]*;/MARKETING_VERSION = $VERSION;/g"
apply "iOS CURRENT_PROJECT_VERSION -> $BUILD" "$PBXPROJ" "s/CURRENT_PROJECT_VERSION = [0-9][0-9]*;/CURRENT_PROJECT_VERSION = $BUILD;/g"
apply "iOS CFBundleShortVersionString -> $VERSION" "$INFO_PLIST" "/CFBundleShortVersionString/{n;s/<string>[^<]*<\\/string>/<string>$VERSION<\\/string>/;}"
apply "iOS CFBundleVersion -> $BUILD" "$INFO_PLIST" "/CFBundleVersion/{n;s/<string>[^<]*<\\/string>/<string>$BUILD<\\/string>/;}"

echo
if [ "$DRY_RUN" = 1 ]; then
  echo "以上为将应用的改动。去掉 --dry-run 后执行即可写入。"
else
  echo "完成。建议确认 git diff 后提交：git diff --stat"
fi
