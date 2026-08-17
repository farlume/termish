#!/usr/bin/env bash
# 004 断网 LOST：WiFi 关闭 → 主动断开（TCP 悬挂修复验证）
# 断言：LOST 事件 + force close 日志（不再长期显示绿色）
source "$(dirname "$0")/../lib.sh"

launch_app
open_demo_terminal
wait_connected 25
adb shell input keyevent HOME
net_wifi_off
assert_log "LOST: force close" "断网触发主动断开（TCP 悬挂修复）" 15
net_wifi_on
# 网络恢复后回前台：会话应自动重连（蜂窝/恢复路径）
adb shell monkey -p dev.termish.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_connected 30
