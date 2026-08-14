#!/bin/bash
# 启动一个本地非 root 测试 sshd（端口 2222），用于传输层集成测试。
# 生成 host/client ed25519 密钥，用 client 公钥做 pubkey 认证。
set -e

DIR="${MSSH_TEST_DIR:-/tmp/mssh_test}"
PORT="${MSSH_TEST_PORT:-2222}"
mkdir -p "$DIR"
cd "$DIR"

[ -f hostkey ] || ssh-keygen -q -t ed25519 -f hostkey -N "" -C "mssh-test-host"
[ -f client ]  || ssh-keygen -q -t ed25519 -f client  -N "" -C "mssh-test-client"
cat client.pub > authorized_keys

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

echo "启动 sshd 于 127.0.0.1:$PORT（日志: $DIR/sshd.log）"
/usr/sbin/sshd -f "$DIR/sshd_config" -E "$DIR/sshd.log"
sleep 0.5
nc -z -w2 127.0.0.1 "$PORT" && echo "OK: 端口 $PORT 已监听"
