#!/bin/bash
# 启动一个本地非 root 测试 sshd（端口 22222），用于传输层集成测试。
# 生成 host/client ed25519 密钥，用 client 公钥做 pubkey 认证。
set -e

DIR="${Termish_TEST_DIR:-/tmp/termish_test}"
PORT="${Termish_TEST_PORT:-22222}"
mkdir -p "$DIR"
cd "$DIR"

[ -f hostkey ] || ssh-keygen -q -t ed25519 -f hostkey -N "" -C "termish-test-host"
[ -f client ]  || ssh-keygen -q -t ed25519 -f client  -N "" -C "termish-test-client"
# CI 里 sshd 以 root 启动、测试进程是普通用户：私钥要可读（仅本地测试用途）
chmod 644 client
cat client.pub > authorized_keys
# 加密私钥集成测试用的第二把公钥（存在则保留）
[ -f enc_client.pub ] && cat enc_client.pub >> authorized_keys

cat > sshd_config <<EOF
Port $PORT
ListenAddress 0.0.0.0
HostKey $DIR/hostkey
AuthorizedKeysFile $DIR/authorized_keys
PasswordAuthentication yes
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM yes
StrictModes no
ChallengeResponseAuthentication no
PidFile $DIR/sshd.pid
LogLevel DEBUG
EOF

# SFTP subsystem（SFTP 集成测试需要；macOS 与 Linux 路径不同）
if [ -x /usr/libexec/sftp-server ]; then
  SFTP_SERVER=/usr/libexec/sftp-server
elif [ -x /usr/lib/openssh/sftp-server ]; then
  SFTP_SERVER=/usr/lib/openssh/sftp-server
fi
echo "Subsystem sftp $SFTP_SERVER" >> "$DIR/sshd_config"

# Ubuntu 的 sshd 需要特权分离目录 /run/sshd（root 可写时自动创建；
# macOS 本地非 root 运行时跳过，不影响）
if [ ! -d /run/sshd ] && [ -w / ]; then
  mkdir -p /run/sshd
fi

echo "启动 sshd 于 127.0.0.1:$PORT（日志: $DIR/sshd.log）"
# 清理上次 run 残留的旧实例（同一 runner 上重复执行时端口可能被占）
if [ -f "$DIR/sshd.pid" ] && kill -0 "$(cat "$DIR/sshd.pid")" 2>/dev/null; then
  echo "停止残留旧 sshd pid=$(cat "$DIR/sshd.pid")"
  kill "$(cat "$DIR/sshd.pid")" && sleep 0.5
fi
/usr/sbin/sshd -f "$DIR/sshd_config" -E "$DIR/sshd.log"
sleep 0.5
# sshd 守护进程 fork 后父进程立即返回 0（set -e 抓不到 bind 失败），
# 且 nc 探测可能命中别的服务误报 OK（如 gitlab 的 2222）——
# 必须以 sshd 日志中的监听确认行作为唯一成功判据。
if grep -q "Server listening on" "$DIR/sshd.log" 2>/dev/null; then
  echo "OK: 端口 $PORT 已监听"
else
  echo "FAIL: sshd 未监听 $PORT，日志如下："
  tail -20 "$DIR/sshd.log" 2>/dev/null || true
  exit 1
fi
