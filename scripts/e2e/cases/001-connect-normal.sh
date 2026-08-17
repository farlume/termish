#!/usr/bin/env bash
# 001 正常连接：冷启动 → 点卡片 → 连接成功
# 断言：logcat 出现 connected（含 kex）+ span 阶段耗时
source "$(dirname "$0")/../lib.sh"

launch_app
open_demo_terminal
wait_connected 25
assert_log "span\[ssh.connect\].*ok" "连接 span 正常结束（阶段耗时完整）" 5
