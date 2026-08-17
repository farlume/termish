#!/usr/bin/env bash
# e2e 测试公共库：logcat 断言 / 网络控制 / demo 服务器控制 / App 控制
# 用法：source lib.sh

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$E2E_DIR/../.." && pwd)"

PASS="${PASS:-0}"
FAIL="${FAIL:-0}"
[ -z "${FAILED_CASES:-}" ] && declare -a FAILED_CASES=()
# 断言基线：log_mark 记录缓冲最后时间戳；wait_log 只匹配此后的日志
LOG_MARK_TS=""

# 操作前调用：记录日志时间基线（连接可能 0.5s 完成，断言只认此后的新日志）
log_mark() {
    LOG_MARK_TS=$(adb logcat -d 2>/dev/null | tail -1 | awk '{print $1, $2}')
}

# ---------- 断言原语 ----------

# 等待 logcat 出现【新】模式：只匹配 log_mark 时间戳之后的行
# （时间基线对各 pattern 独立；计数/行数方案会被环形缓冲与快速完成的操作干扰）
wait_log() {
    local pattern="$1" timeout="${2:-15}"
    local waited=0
    while [ $waited -lt $timeout ]; do
        if adb logcat -d 2>/dev/null | awk -v ts="$LOG_MARK_TS" '$1" "$2 > ts' | grep -q "$pattern"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# 断言 logcat 出现模式；失败打印消息并退出
assert_log() {
    local pattern="$1" msg="$2" timeout="${3:-15}"
    if wait_log "$pattern" "$timeout"; then
        echo "  ✅ $msg"
        return 0
    fi
    echo "  ❌ ${msg}（logcat 未出现: ${pattern}）"
    return 1
}

# 等待 App 进程启动
wait_app() {
    local timeout="${1:-15}"
    local waited=0
    while [ $waited -lt $timeout ]; do
        if adb shell pidof dev.termish.app >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# ---------- App 控制 ----------

# 冷启动 App 到首页
launch_app() {
    adb shell am force-stop dev.termish.app
    adb shell monkey -p dev.termish.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    wait_app
    sleep 3  # 等启动动画/首帧（进程出现 ≠ UI 可交互）
}

# 前置检查：demo 主机存在（首页列表含 10.0.2.2）且可连接
# （密码需已存 SecretStore——连接过一次即已保存；无密码会弹认证框卡住）
ensure_demo_host() {
    local waited=0
    while [ $waited -lt 20 ]; do
        adb shell uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1
        if adb shell cat /sdcard/e2e.xml 2>/dev/null | grep -q "10.0.2.2"; then
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
    done
    echo "  ❌ 前置条件缺失：App 主机列表没有 10.0.2.2（demo）"
    echo "     请先在 App 添加主机：termish@10.0.2.2:2223，密码 termish-demo，并成功连接一次（保存凭据）"
    exit 1
}

# 点击 demo 主机卡片进入终端页（假设在首页，卡片为第二项）
open_demo_terminal() {
    ensure_demo_host
    # 等首页渲染完成（进程出现 ≠ UI 就绪）
    local waited=0
    while [ $waited -lt 20 ]; do
        adb shell uiautomator dump /sdcard/e2e.xml >/dev/null 2>&1
        if adb shell cat /sdcard/e2e.xml 2>/dev/null | grep -q "我的主机"; then
            break
        fi
        sleep 2
        waited=$((waited + 2))
    done
    # 连接极快（~0.5s）：先记录日志基线再 tap，断言只认此后的新日志
    log_mark
    adb shell input tap 285 568
    sleep 2
}

# 等待连接成功（logcat 出现 connected）
wait_connected() {
    assert_log "connected .* kex=" "连接成功" "${1:-20}"
}

# ---------- 网络控制 ----------

net_wifi_off() { adb shell svc wifi disable; }
net_wifi_on() { adb shell svc wifi enable; sleep 2; }
net_airplane_on() { adb shell cmd connectivity airplane-mode enable; }
net_airplane_off() { adb shell cmd connectivity airplane-mode disable; sleep 2; }

# ---------- demo 服务器控制 ----------

demo_stop() {
    docker stop termish-demo >/dev/null 2>&1
    # 等待端口真正关闭（优雅停止有延迟，否则断言时连接可能仍成功）
    local waited=0
    while [ $waited -lt 15 ]; do
        if ! nc -z -w 1 127.0.0.1 2223 >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}
demo_start() { docker start termish-demo >/dev/null 2>&1; sleep 2; }
demo_running() { docker ps --format '{{.Names}}' 2>/dev/null | grep -q termish-demo; }

# ---------- case 运行器 ----------

run_case() {
    local script="$1" name
    name="$(basename "$script" .sh)"
    echo ""
    echo "========== $name =========="
    if bash "$script"; then
        PASS=$((PASS + 1))
        echo "  ✅ PASS: $name"
        return 0
    else
        FAIL=$((FAIL + 1))
        FAILED_CASES+=("$name")
        echo "  ❌ FAIL: $name"
        return 1
    fi
}

summary() {
    echo ""
    echo "======================================"
    echo "结果: $PASS 通过, $FAIL 失败"
    if [ $FAIL -gt 0 ]; then
        printf '失败: %s\n' "${FAILED_CASES[@]}"
        exit 1
    fi
    exit 0
}
