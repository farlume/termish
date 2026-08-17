#!/usr/bin/env bash
# e2e 测试主入口：跑全部或指定 case
#
# 用法:
#   ./scripts/e2e/e2e-run.sh              # 跑全部 case
#   ./scripts/e2e/e2e-run.sh 001           # 只跑 001-*
#   ./scripts/e2e/e2e-run.sh -v            # 详细输出（不吞 case 内部输出）
#
# 前置条件:
#   - 模拟器已启动（adb devices 可见）
#   - demo 服务器容器 termish-demo 在运行（docker）
#   - debug 包已安装（make run / installDebug）
set -u

E2E_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$E2E_DIR/lib.sh"

FILTER="${1:-all}"
VERBOSE=0
[ "${1:-}" = "-v" ] && { FILTER="all"; VERBOSE=1; }
[ "${2:-}" = "-v" ] && VERBOSE=1

# 前置检查
if ! adb devices | grep -q "device$"; then
    echo "❌ 无可用设备/模拟器（adb devices）"
    exit 1
fi
if ! demo_running; then
    echo "❌ demo 服务器未运行（docker start termish-demo）"
    exit 1
fi
if ! adb shell pidof dev.termish.app >/dev/null 2>&1; then
    echo "⚠️ App 未运行，将自动启动"
    launch_app
fi

echo "e2e 测试开始（filter=${FILTER}）"
echo "设备: $(adb devices | sed -n '2p' | awk '{print $1}')"
adb logcat -c

CASES=$(ls "$E2E_DIR/cases/"/*.sh 2>/dev/null | sort)
[ $? -ne 0 ] && { echo "❌ cases/ 目录为空"; exit 1; }

for case in $CASES; do
    name="$(basename "$case")"
    if [ "$FILTER" != "all" ]; then
        case "$name" in
            $FILTER-*|*"$FILTER"*) ;;
            *) continue ;;
        esac
    fi
    if [ "$VERBOSE" = 1 ]; then
        run_case "$case"
    else
        # 计数由 run_case 内部完成；这里只静默执行并展示结果
        run_case "$case" > /tmp/e2e-case.log 2>&1
        rc=$?
        if [ $rc -eq 0 ]; then
            echo "  ✅ PASS: $name"
        else
            echo "  ❌ FAIL: $name"
            cat /tmp/e2e-case.log | grep -E "❌|Error|error" | head -5
        fi
    fi
done

summary
