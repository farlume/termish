#!/usr/bin/env bash
# 002 连接拒绝：服务器关闭 → 快速失败（非超时）
# 断言：connect failed 且耗时 < 10s（无 SLOW 特征）
source "$(dirname "$0")/../lib.sh"

launch_app
demo_stop
open_demo_terminal
# 端口拒绝：TCP 立即 RST → 快速失败（<10s 无 SLOW 标记）
if assert_log "connect failed after [0-9]+ms" "连接快速失败（端口拒绝）" 20; then
    if adb logcat -d 2>/dev/null | grep -qE "connect failed after (1[0-9]|[2-9][0-9])000"; then
        echo "  ❌ 失败耗时异常（>10s，疑似超时而非拒绝）"
        demo_start
        exit 1
    fi
    echo "  ✅ 失败耗时 < 10s（快速拒绝，非黑洞超时）"
else
    echo "  ❌ 未观察到 connect failed"
    demo_start
    exit 1
fi
demo_start
