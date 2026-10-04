set -e
PORT=@SCREEN_PORT@
OS=$(uname)
PLIST="$HOME/Library/LaunchAgents/dev.termish.screen.plist"
@NATIVE_SOURCE@
# ---- ffmpeg：缺失时安装（查找路径与读流脚本一致，避免装完仍被非交互
# PATH 误报缺失）。macOS 走 brew/静态包；Linux 需 apt（sudo）——
# 检测不到且无 apt 权限时给出明确提示（日志可见）----
FF=""
for cand in $(command -v ffmpeg 2>/dev/null) "$HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then FF="$cand"; break; fi
done
# Linux 系统依赖共用同一授权入口。不能只在缺 ffmpeg 时定义，否则
# 最终画面可看但被误报为“不支持远程控制”。
if [ "$OS" = "Linux" ]; then
  ADMIN_AVAILABLE=1
  if [ "$(id -u)" = "0" ]; then
    run_admin() { "$@"; }
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    run_admin() { sudo -n "$@"; }
  elif [ "${TERMISH_SUDO_STDIN:-}" = "1" ] && command -v sudo >/dev/null 2>&1; then
    run_admin() { sudo -S -p '' "$@"; }
  else
    ADMIN_AVAILABLE=0
    run_admin() { return 1; }
  fi
fi
if [ -z "$FF" ]; then
  if [ "$OS" = "Darwin" ]; then
  if command -v brew >/dev/null 2>&1; then
    echo "==> 正在通过 Homebrew 安装 ffmpeg（约几分钟）"
    brew install ffmpeg
    # brew 装完重新定位（PATH 受限会话里 command -v 仍可能失败，兜底 brew 前缀）
    FF="$(command -v ffmpeg 2>/dev/null)"
    [ -z "$FF" ] && FF="/opt/homebrew/bin/ffmpeg"
    [ -x "$FF" ] || FF="/usr/local/bin/ffmpeg"
  else
    echo "==> 正在下载 ffmpeg 静态版到 ~/bin"
    mkdir -p "$HOME/bin"
    curl -fsSL -o /tmp/termish-ffmpeg.zip https://evermeet.cx/ffmpeg/getrelease/zip
    rm -rf /tmp/termish-ffmpeg && mkdir -p /tmp/termish-ffmpeg
    unzip -q /tmp/termish-ffmpeg.zip -d /tmp/termish-ffmpeg
    cp /tmp/termish-ffmpeg/ffmpeg "$HOME/bin/ffmpeg"
    chmod +x "$HOME/bin/ffmpeg"
    FF="$HOME/bin/ffmpeg"
  fi
  else
    # Linux：root 或免密 sudo 时自动安装；App 已提供密码时走 sudo -S；
    # 缺少可用授权方式时输出与发行版匹配的手动命令。
    if [ "$ADMIN_AVAILABLE" != "1" ]; then
      if command -v apt-get >/dev/null 2>&1; then
        echo "==> Linux 需要管理员权限：请先在终端执行 sudo apt-get update && sudo apt-get install -y ffmpeg，然后重试" >&2
      elif command -v dnf >/dev/null 2>&1; then
        echo "==> Linux 需要管理员权限：请先在终端执行 sudo dnf install -y ffmpeg，然后重试" >&2
      elif command -v pacman >/dev/null 2>&1; then
        echo "==> Linux 需要管理员权限：请先在终端执行 sudo pacman -S --needed ffmpeg，然后重试" >&2
      else
        echo "==> Linux 需要 ffmpeg：请用系统包管理器安装后重试" >&2
      fi
      exit 1
    fi
    if command -v apt-get >/dev/null 2>&1; then
      echo "==> 正在通过 apt 安装 ffmpeg"
      # update + install 放进同一次 sudo：即使远端禁用 sudo 时间戳缓存，
      # 也只读取一次密码，不会在第二条命令等待额外输入。
      run_admin sh -c 'apt-get update -qq && apt-get install -y -qq ffmpeg'
    elif command -v dnf >/dev/null 2>&1; then
      echo "==> 正在通过 dnf 安装 ffmpeg"
      run_admin dnf install -y -q ffmpeg
    elif command -v pacman >/dev/null 2>&1; then
      echo "==> 正在通过 pacman 安装 ffmpeg"
      run_admin pacman -S --needed --noconfirm ffmpeg
    else
      echo "==> 无法识别 Linux 包管理器，请手动安装 ffmpeg 后重试" >&2
      exit 1
    fi
    FF="$(command -v ffmpeg 2>/dev/null)"
    [ -n "$FF" ] || FF="/usr/bin/ffmpeg"
  fi
fi
FF_REAL=$(readlink -f "$FF" 2>/dev/null || echo "$FF")
echo "==> ffmpeg: $FF_REAL"
# 每个远端账号独立的 256-bit bearer token：回环 TCP 也会被同机
# 其它 OS 用户访问，不能把“只监听 127.0.0.1”当作认证边界。
TOKEN_FILE="$HOME/.termish-screen.token"
TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE" 2>/dev/null || true)"
if ! printf '%s' "$TOKEN" | grep -Eq '^[0-9a-fA-F]{64}$'; then
  umask 077
  TOKEN_TMP="$TOKEN_FILE.tmp.$$"
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TOKEN_TMP"
  mv "$TOKEN_TMP" "$TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE"
# X11 text paste uses xclip; Wayland capture uses the consent-scoped GStreamer PipeWire source.
if [ "$OS" = "Linux" ]; then
  if [ "${TERMISH_SESSION_TYPE:-}" = "wayland" ]; then
    if ! command -v gst-launch-1.0 >/dev/null 2>&1 || ! gst-inspect-1.0 pipewiresrc >/dev/null 2>&1 || ! gst-inspect-1.0 y4menc >/dev/null 2>&1; then
      [ "$ADMIN_AVAILABLE" = "1" ] || { echo '==> Wayland capture dependencies require administrator permission' >&2; exit 1; }
      if command -v apt-get >/dev/null 2>&1; then
        run_admin sh -c 'apt-get update -qq && apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-base'
      elif command -v dnf >/dev/null 2>&1; then
        run_admin dnf install -y -q gstreamer1-plugins-base pipewire-gstreamer gstreamer1-tools
      elif command -v pacman >/dev/null 2>&1; then
        run_admin pacman -S --needed --noconfirm gst-plugins-base gst-plugin-pipewire gstreamer
      else
        echo '==> Install GStreamer tools, PipeWire and base plugins, then retry' >&2; exit 1
      fi
    fi
  elif ! command -v xclip >/dev/null 2>&1; then
    [ "$ADMIN_AVAILABLE" = "1" ] || { echo '==> X11 text paste requires xclip and administrator permission' >&2; exit 1; }
    if command -v apt-get >/dev/null 2>&1; then
      run_admin sh -c 'apt-get update -qq && apt-get install -y -qq xclip'
    elif command -v dnf >/dev/null 2>&1; then
      run_admin dnf install -y -q xclip
    elif command -v pacman >/dev/null 2>&1; then
      run_admin pacman -S --needed --noconfirm xclip
    else
      echo '==> Install xclip, then retry' >&2; exit 1
    fi
  fi
fi
mkdir -p "$APP_DIR"
"$TERMISH_NATIVE_EXEC" --write-config "$APP_DIR/screen-service.json" "$PORT" "$FF_REAL"
"$TERMISH_NATIVE_EXEC" --check-config --config "$APP_DIR/screen-service.json"
restore_macos_service() {
  launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
  rm -rf "$APP_BUNDLE"
  if [ -d "$APP_BACKUP" ]; then mv "$APP_BACKUP" "$APP_BUNDLE"; fi
  if [ -f "$PLIST_BACKUP" ]; then
    mv "$PLIST_BACKUP" "$PLIST"
    launchctl bootstrap gui/$(id -u) "$PLIST" 2>/dev/null || true
  else
    rm -f "$PLIST"
  fi
}
if [ "$OS" = "Darwin" ]; then
  # Only replace this service after checksum, bundle signature and config pass.
  PLIST_BACKUP="$PLIST.termish-previous"
  rm -f "$PLIST_BACKUP"
  if [ -f "$PLIST" ]; then cp "$PLIST" "$PLIST_BACKUP"; fi
  launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
  APP_BACKUP="$APP_DIR/.Termish Helper.previous"
  rm -rf "$APP_BACKUP"
  if [ -d "$APP_BUNDLE" ]; then mv "$APP_BUNDLE" "$APP_BACKUP"; fi
  if ! mv "$TERMISH_APP_STAGE" "$APP_BUNDLE"; then
    restore_macos_service
    exit 1
  fi
  rm -f "$TERMISH_NATIVE_STAGE"
  /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP_BUNDLE" || true
else
  mv "$TERMISH_NATIVE_STAGE" "$NATIVE"
fi
"$NATIVE" --licenses > "$APP_DIR/screen-service.NOTICE"
printf 'rust\n' > "$APP_DIR/screen-service.backend"
# ---- 服务启动：macOS 用 LaunchAgent（GUI 域录屏权限）；
# Linux 用 systemd 用户服务（桌面自启动兜底），机器重启后随图形登录
# 自动恢复。旧版仅 nohup，进程在本次 SSH 断开后能活、重启后必丢。----
if [ "$OS" = "Darwin" ]; then
mkdir -p "$HOME/Library/LaunchAgents"
if ! "$NATIVE" --write-launch-agent "$PLIST"; then
  restore_macos_service
  exit 1
fi
launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
sleep 1
# relay 启动时会按自身 PID 文件清理上一轮孤儿 ffmpeg；这里不能
# pkill 全部 ffmpeg，否则会误杀用户自己的转码/录制任务。
if ! launchctl bootstrap gui/$(id -u) "$PLIST"; then
  restore_macos_service
  exit 1
fi
sleep 1
# 验证用 launchctl（不碰连接：探测连接-断开会打断 relay 的当前服务周期；
# 也不用 pgrep：安装脚本自身的 zsh 命令行含脚本文本会误匹配）
if launchctl print gui/$(id -u)/dev.termish.screen 2>/dev/null | grep -q "state = running" \
  && lsof -nP -iTCP:$((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1; then
  echo @RELAY_VERSION@ > "$HOME/.termish-screen.version"
  rm -rf "$APP_BACKUP"
  rm -f "$PLIST_BACKUP"
  rm -f "$APP_DIR/screen-service"
  echo "==> TERMISH_SCREEN_OK"
else
  restore_macos_service
  echo "==> 服务未启动（检查 ~/Library/Logs/termish-screen.err）" >&2
  exit 1
fi
else
# Linux：重启 relay，并注册用户级持久服务。
# ⚠️ 不用 pkill -f：安装脚本自身（sh -c）命令行含
# 脚本文本，-f 全匹配会把自己杀掉（macOS 分支同款坑，用户反馈：
# Ubuntu 引导安装失败）——用 PID 文件精确清理
RELAY_PID="$HOME/.termish-screen.pid"
stop_termish_relay_pid() {
  OLD_PID="$1"
  # 安装脚本启用了 set -e；PID 文件失效是正常升级场景，函数必须
  # 显式成功返回，否则会在写入新 relay 后、启动它之前提前退出。
  case "$OLD_PID" in ''|*[!0-9]*) return 0 ;; esac
  [ -r "/proc/$OLD_PID/cmdline" ] || return 0
  OLD_CMD="$(tr '\0' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)"
  case "$OLD_CMD" in
    *"$NATIVE"*|*"$HOME/.termish-screen-launch.sh"*) kill "$OLD_PID" 2>/dev/null || true ;;
  esac
  return 0
}
if [ -f "$RELAY_PID" ]; then
  stop_termish_relay_pid "$(cat "$RELAY_PID" 2>/dev/null)"
  rm -f "$RELAY_PID"
fi
# v31 及更早版本可能没有可靠 PID 文件，或失败的新进程覆盖过 PID。
# 从监听端口找到旧进程后仍逐项核对 cmdline 必须包含当前 relay 绝对路径，
LISTENER_PID=""
if command -v lsof >/dev/null 2>&1; then
  LISTENER_PID="$(lsof -t -iTCP:$PORT -sTCP:LISTEN 2>/dev/null | head -1)"
elif command -v fuser >/dev/null 2>&1; then
  LISTENER_PID="$(fuser -n tcp "$PORT" 2>/dev/null | awk '{print $1}')"
fi
stop_termish_relay_pid "$LISTENER_PID"
sleep 0.5
# 启动包装器每次运行都重新探测 DISPLAY/XAUTHORITY。Xwayland 的 display
# 号和授权文件会在重启后变化，不能把安装当时的值固化进 service unit。
RUNNER="$HOME/.termish-screen-launch.sh"
LOG="$HOME/.termish-screen.log"
cat > "$RUNNER" <<'TERMISH_EOF'
#!/bin/sh
NATIVE="$HOME/Library/Application Support/termish/screen-service"
LOG="$HOME/.termish-screen.log"
# relay 只记录异常、码率调档和编码器自愈；仍限制日志体积，避免长期
# 常驻后无限增长。保留最后 1 MiB 足够排查最近一次会话。
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 5242880 ]; then
  tail -c 1048576 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
fi
while :; do
  USER_RUNTIME="/run/user/$(id -u)"
  XDISP=":0"
  XPID=""
  GRAPHICAL=0
  LOCAL_SESSION_TYPE=""
  # logind 是会话类型的权威来源。Wayland 也会启动 Xwayland，若先
  # 看进程会误走 x11grab 并输出黑色根窗口。
  if command -v loginctl >/dev/null 2>&1; then
    for sid in $(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="$(id -u)" '$2 == uid { print $1 }'); do
      SESSION_TYPE="$(loginctl show-session "$sid" -p Type --value 2>/dev/null)"
      SESSION_REMOTE="$(loginctl show-session "$sid" -p Remote --value 2>/dev/null)"
      case "$SESSION_TYPE:$SESSION_REMOTE" in
        wayland:no|x11:no)
          GRAPHICAL=1
          LOCAL_SESSION_TYPE="$SESSION_TYPE"
          SESSION_DISPLAY="$(loginctl show-session "$sid" -p Display --value 2>/dev/null)"
          [ -n "$SESSION_DISPLAY" ] && XDISP="$SESSION_DISPLAY"
          break
          ;;
      esac
    done
  fi
  if command -v pgrep >/dev/null 2>&1; then
    # 只选择当前用户的图形服务器；GDM greeter 属于另一用户。
    XPID="$(pgrep -u "$(id -u)" -x Xwayland 2>/dev/null | head -1)"
    if [ -z "$XPID" ]; then
      XPID="$(pgrep -u "$(id -u)" -x Xorg 2>/dev/null | head -1)"
    fi
  fi
  if [ -n "$XPID" ] && [ -r "/proc/$XPID/cmdline" ]; then
    DETECTED_DISPLAY="$(tr '\0' '\n' < "/proc/$XPID/cmdline" | grep -E '^:[0-9]+$' | head -1)"
    [ -n "$DETECTED_DISPLAY" ] && XDISP="$DETECTED_DISPLAY"
    if [ "$GRAPHICAL" != "1" ] && [ "$(basename "$(readlink "/proc/$XPID/exe" 2>/dev/null)")" = "Xorg" ]; then
      GRAPHICAL=1
      LOCAL_SESSION_TYPE="x11"
    fi
  fi
  XNUM="${XDISP#:}"
  if [ "$GRAPHICAL" = "1" ]; then
    export XDG_RUNTIME_DIR="$USER_RUNTIME"
    export DBUS_SESSION_BUS_ADDRESS="unix:path=$USER_RUNTIME/bus"
    if [ "$LOCAL_SESSION_TYPE" = "wayland" ]; then
      WAYLAND_SOCKET="$(find "$USER_RUNTIME" -maxdepth 1 -type s -name 'wayland-*' -printf '%f\n' 2>/dev/null | sort | head -1)"
      if [ -z "$WAYLAND_SOCKET" ]; then
        sleep 2
        continue
      fi
      export XDG_SESSION_TYPE=wayland
      export WAYLAND_DISPLAY="$WAYLAND_SOCKET"
      COMPOSITOR_PID="$(pgrep -u "$(id -u)" -x kwin_wayland 2>/dev/null | head -1)"
      if [ -n "$COMPOSITOR_PID" ] && [ -r "/proc/$COMPOSITOR_PID/environ" ]; then
        CURRENT_DESKTOP="$(tr '\0' '\n' < "/proc/$COMPOSITOR_PID/environ" | sed -n 's/^XDG_CURRENT_DESKTOP=//p' | head -1)"
        [ -n "$CURRENT_DESKTOP" ] && export XDG_CURRENT_DESKTOP="$CURRENT_DESKTOP"
      fi
    else
      [ -S "/tmp/.X11-unix/X$XNUM" ] || { sleep 2; continue; }
      export XDG_SESSION_TYPE=x11
      unset WAYLAND_DISPLAY
    fi
    if [ -S "/tmp/.X11-unix/X$XNUM" ]; then
      export DISPLAY="$XDISP"
    fi
    XAUTH=""
    if [ -n "$XPID" ] && [ -r "/proc/$XPID/cmdline" ]; then
      XAUTH="$(tr '\0' '\n' < "/proc/$XPID/cmdline" | awk 'take { print; exit } $0 == "-auth" { take=1 }')"
    fi
    if [ -z "$XAUTH" ] && [ -f "/run/user/$(id -u)/gdm/Xauthority" ]; then
      XAUTH="/run/user/$(id -u)/gdm/Xauthority"
    fi
    if [ -z "$XAUTH" ] && [ -f "$HOME/.Xauthority" ]; then
      XAUTH="$HOME/.Xauthority"
    fi
    if [ -n "$XAUTH" ] && [ -r "$XAUTH" ]; then
      export XAUTHORITY="$XAUTH"
    fi
    exec "$NATIVE" >> "$LOG" 2>&1
  fi
  sleep 2
done
TERMISH_EOF
chmod 700 "$RUNNER"

SERVICE_STARTED=0
USER_RUNTIME="/run/user/$(id -u)"
UNIT_DIR="$HOME/.config/systemd/user"
UNIT="$UNIT_DIR/dev.termish.screen.service"
if command -v systemctl >/dev/null 2>&1 && [ -S "$USER_RUNTIME/bus" ]; then
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT" <<TERMISH_EOF
[Unit]
Description=Termish screen relay
After=graphical-session.target

[Service]
Type=simple
ExecStart=%h/.termish-screen-launch.sh
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
TERMISH_EOF
  export XDG_RUNTIME_DIR="$USER_RUNTIME"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=$USER_RUNTIME/bus"
  systemctl --user daemon-reload
  # enable --now 不会重启已运行的旧 unit；升级 relay 时必须显式 restart，
    if systemctl --user enable dev.termish.screen.service \
    && systemctl --user restart dev.termish.screen.service; then
    SERVICE_STARTED=1
    rm -f "$HOME/.config/autostart/dev.termish.screen.desktop"
    echo "==> systemd 用户服务：已启用（重启后自动恢复）"
  fi
fi
if [ "$SERVICE_STARTED" != "1" ]; then
  # 没有可用的 systemd user bus（部分精简桌面）时，通过 XDG autostart
  # 保证下次图形登录自动启动；当前安装仍用 nohup 立即拉起。
  mkdir -p "$HOME/.config/autostart"
  cat > "$HOME/.config/autostart/dev.termish.screen.desktop" <<'TERMISH_EOF'
[Desktop Entry]
Type=Application
Name=Termish Screen Relay
Exec=/bin/sh -c "$HOME/.termish-screen-launch.sh"
X-GNOME-Autostart-enabled=true
NoDisplay=true
TERMISH_EOF
  nohup "$RUNNER" >/dev/null 2>&1 &
  echo $! > "$RELAY_PID"
  echo "==> 桌面自启动服务：已启用（重启后自动恢复）"
fi
sleep 1.5
# 验证控制面与回环视频端口都在监听；不建立探测连接（会触发 ffmpeg）。
if (lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":$PORT ") \
  && (lsof -nP -iTCP:$((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":$((PORT + 2)) "); then
  echo @RELAY_VERSION@ > "$HOME/.termish-screen.version"
  echo "==> TERMISH_SCREEN_OK"
else
  echo "==> 服务未启动（检查 $LOG）" >&2
  exit 1
fi
fi
