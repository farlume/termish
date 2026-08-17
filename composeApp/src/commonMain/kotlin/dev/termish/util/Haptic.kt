package dev.termish.util

/**
 * 轻触感反馈（工具栏按键 / 连接成功等操作反馈）。
 * 由设置页「触感反馈」开关控制（调用方判断）。
 * Android：系统 KEYBOARD_TAP 轻击（无需权限）；iOS：UIImpactFeedbackGenerator；
 * 桌面：无操作。
 */
expect fun hapticTick()
