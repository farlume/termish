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
#   - CHANGELOG.md
#       把 [Unreleased] 段落落盘为 [X.Y.Z] - <当天日期>，并重建文末版本链接块
#       （有 git remote 时用 compare/releases 真实链接，否则保留 TODO 注释占位）

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
CHANGELOG="CHANGELOG.md"
TODAY=$(date +%F)

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

# ---------------------------------------------------------------------------
# CHANGELOG 联动：把 [Unreleased] 累积的内容落盘为 [X.Y.Z] - <今天>，
# 并重建文末版本链接块。幂等：已有 [X.Y.Z] 段落则跳过（重复 bump 安全）。
# CI 打 vX.Y.Z tag 时会校验该段落存在（见 build.yml 版本一致性检查）。
# ---------------------------------------------------------------------------
update_changelog() {
  [ -f "$CHANGELOG" ] || return 0
  if grep -q "^## \[$VERSION\] - " "$CHANGELOG"; then
    echo "  [跳过] CHANGELOG 已有 [$VERSION] 段落"
    return 0
  fi
  if ! grep -q '^## \[Unreleased\]' "$CHANGELOG"; then
    echo "  [警告] $CHANGELOG 缺少 ## [Unreleased] 段落，未动 CHANGELOG" >&2
    return 0
  fi

  local tmp
  tmp=$(mktemp)
  # 单趟 awk：① [Unreleased] 正文暂存 → 落盘到新版本段落；② 文末旧链接块
  # 全部丢弃（后面统一重建，保证新增版本的 compare 链接不漏）
  awk -v ver="$VERSION" -v today="$TODAY" '
    /^## \[Unreleased\][[:space:]]*$/ && state == 0 { print; state = 1; next }
    state == 1 && /^## \[/ {
      # 先输出新版本段头，再回放暂存的正文（裁掉尾部多余空行，段间留一空行）
      while (n > 0 && body[n - 1] ~ /^[[:space:]]*$/) n--
      printf "\n## [%s] - %s\n", ver, today
      for (i = 0; i < n; i++) print body[i]
      print ""
      state = 2
      print
      next
    }
    state == 1 { body[n++] = $0; next }
    /^\[[^]]+\]:/ { next }  # 旧链接定义行：末尾统一重建
    { print }
    END {
      # [Unreleased] 是文件里最后一段（无后续 ## [）时也要落盘
      if (state == 1) {
        while (n > 0 && body[n - 1] ~ /^[[:space:]]*$/) n--
        printf "\n## [%s] - %s\n", ver, today
        for (i = 0; i < n; i++) print body[i]
        print ""
      }
    }
  ' "$CHANGELOG" > "$tmp"

  # Unreleased 正文为空时提醒（空发版通常是忘了记 changelog），但不阻断
  if ! awk '/^## \[Unreleased\]/{f=1;next} /^## \[/{f=0} f' "$CHANGELOG" | grep -q '[^[:space:]]'; then
    echo "  [警告] [Unreleased] 正文为空——确认本次发版无需记录 changelog" >&2
  fi

  # 重建版本链接块：版本按文档序（新→旧）；每个版本 compare 到上一旧版本，
  # 最旧版本链到 releases/tag。无 git remote 时保留 TODO 占位（与现状一致）
  local base=""
  local remote
  if remote=$(git remote get-url origin 2>/dev/null); then
    base=$(echo "$remote" | sed -E 's#^git@([^:]+):#https://\1/#; s#\.git$##')
  fi
  local versions=()
  local v
  while IFS= read -r v; do versions+=("$v"); done \
    < <(grep -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' "$tmp" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  if [ -n "$base" ]; then
    echo "[Unreleased]: $base/compare/v$VERSION...HEAD" >> "$tmp"
    local i older
    for i in "${!versions[@]}"; do
      v="${versions[$i]}"
      if [ $((i + 1)) -lt ${#versions[@]} ]; then
        older="${versions[$((i + 1))]}"
        echo "[$v]: $base/compare/v$older...v$v" >> "$tmp"
      else
        echo "[$v]: $base/releases/tag/v$v" >> "$tmp"
      fi
    done
  else
    echo "[Unreleased]: <!-- TODO: 配上 git remote 后补 compare 链接 -->" >> "$tmp"
    for v in "${versions[@]}"; do
      echo "[$v]: <!-- TODO: 配上 git remote 后补 releases 链接 -->" >> "$tmp"
    done
  fi

  if diff -q "$CHANGELOG" "$tmp" > /dev/null; then
    rm -f "$tmp"
    return 0
  fi
  echo "  [$([ "$DRY_RUN" = 1 ] && echo 预览 || echo 已改)] CHANGELOG [$VERSION] - $TODAY（[Unreleased] 落盘 + 链接块重建）"
  if [ "$DRY_RUN" = 1 ]; then
    diff -u "$CHANGELOG" "$tmp" | tail -n +3 | sed 's/^/    /' || true
    rm -f "$tmp"
  else
    mv "$tmp" "$CHANGELOG"
  fi
}

update_changelog

echo
if [ "$DRY_RUN" = 1 ]; then
  echo "以上为将应用的改动。去掉 --dry-run 后执行即可写入。"
else
  echo "完成。建议确认 git diff 后提交：git diff --stat"
fi
