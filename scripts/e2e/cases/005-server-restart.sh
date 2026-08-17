#!/usr/bin/env bash
# 005 服务器重启：docker restart → RST → 自动重连成功（快速恢复）
# 断言：onClosed（断开检测）+ 第二次 connected（重连成功）
source "$(dirname "$0")/../lib.sh"

launch_app
open_demo_terminal
wait_connected 25

# 快速重启（stop+start 间隙 ~2s，重连退避 2s 后服务器已恢复）
docker restart termish-demo >/dev/null 2>&1
assert_log "onClosed .*status=CONNECTED" "服务器重启触发断开检测" 15
# 自动重连应成功（服务器已恢复）：第二次 connected
assert_log "connected .* kex=" "自动重连成功" 30
