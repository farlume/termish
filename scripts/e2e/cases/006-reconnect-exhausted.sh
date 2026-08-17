#!/usr/bin/env bash
# 006 重连耗尽 → 后台通知：服务器关闭 + 退后台 → 3 次重连失败 → 通知
# 断言：reconnect exhausted + 系统通知（termish_session channel）
source "$(dirname "$0")/../lib.sh"

launch_app
open_demo_terminal
wait_connected 25
adb shell input keyevent HOME

demo_stop
assert_log "reconnect exhausted" "重连 3 次耗尽（含 5s 超时缩短验证）" 90
sleep 2
if adb shell dumpsys notification 2>/dev/null | grep -q "termish_session"; then
    echo "  ✅ 后台通知出现（重连失败）"
else
    echo "  ❌ 通知缺失"
    demo_start
    exit 1
fi
demo_start
