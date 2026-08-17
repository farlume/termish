#!/usr/bin/env bash
# 003 黑洞超时：宿主机 pf DROP 2223 → TCP 悬挂 → 超时失败（SLOW 特征）
# 前置：macOS 宿主机，sudo -n（免密）可用；不可用时跳过
# 断言：connect SLOW/FAIL after >10s
source "$(dirname "$0")/../lib.sh"

if ! sudo -n true 2>/dev/null; then
    echo "  ⚠️ sudo -n 不可用（需要免密 pf 权限），跳过黑洞场景"
    exit 0
fi

# pf 丢包规则（DROP 而非 REJECT：REJECT 快速拒绝，测不到超时）
sudo pfctl -e >/dev/null 2>&1
echo "block drop out proto tcp from any to 127.0.0.1 port 2223" | sudo pfctl -f - >/dev/null 2>&1

launch_app
open_demo_terminal
assert_log "connect SLOW/FAIL after" "黑洞超时检测（>10s SLOW 特征）" 40

# 恢复 pf
sudo pfctl -f /etc/pf.conf >/dev/null 2>&1 || sudo pfctl -d >/dev/null 2>&1
