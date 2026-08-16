#!/bin/bash
# 启动一个本地非 root 测试 sshd（端口 2222），用于传输层集成测试。
# 生成 host/client ed25519 密钥，用 client 公钥做 pubkey 认证。
set -e

DIR="${Termish_TEST_DIR:-/tmp/termish_test}"
PORT="${Termish_TEST_PORT:-2222}"
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
ListenAddress 127.0.0.1
HostKey $DIR/hostkey
AuthorizedKeysFile $DIR/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM no
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

echo "启动 sshd 于 127.0.0.1:$PORT（日志: $DIR/sshd.log）"
/usr/sbin/sshd -f "$DIR/sshd_config" -E "$DIR/sshd.log"
sleep 0.5
nc -z -w2 127.0.0.1 "$PORT" && echo "OK: 端口 $PORT 已监听"
