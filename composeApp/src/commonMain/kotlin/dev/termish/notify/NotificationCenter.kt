package dev.termish.notify

import dev.termish.data.AppSettings

/**
 * 通知事件清单。每个事件有独立开关（设置页「通知」二级页可关），
 * 统一由 [NotificationCenter] 分发——业务方只调 post，不碰平台 API。
 */
enum class NotificationEvent(val id: String) {
    /** 连接意外断开（后台时）。 */
    CONNECTION_LOST("connection_lost"),
    /** 自动重连耗尽仍失败。 */
    RECONNECT_FAILED("reconnect_failed"),
    /** SFTP 传输完成（预留）。 */
    TRANSFER_DONE("transfer_done"),
    /** Agent 任务完成（预留：herdr 任务通知）。 */
    AGENT_TASK("agent_task"),
}

/**
 * 统一通知中心：总开关 + 事件开关 + 前后台过滤 + 平台分发。
 * App 启动时由 AppRoot 注入设置读取器与前后台状态。
 */
object NotificationCenter {

    /** App 启动时注入（读取最新 AppSettings）。 */
    var settingsProvider: (() -> AppSettings)? = null

    /** 前后台状态（AppRoot 经 observeAppLifecycle 维护）：前台不弹通知（有 UI banner）。 */
    @Volatile
    var foreground: Boolean = true

    /** 通知动作回调（AppRoot 注册）：「重新连接」动作触发时按 hostId 重连会话。 */
    var onReconnectRequest: ((hostId: String) -> Unit)? = null

    /** 发通知：总开关/事件开关/前台过滤通过后交平台实现。 */
    fun post(
        event: NotificationEvent,
        title: String,
        body: String,
        id: Int = event.ordinal,
        /** 动作按钮：非空时平台通知带「重新连接」动作（点击按 hostId 重连）。 */
        hostId: String? = null,
    ) {
        if (foreground) return
        val s = settingsProvider?.invoke() ?: return
        if (!s.notificationEnabled) return
        if (event.id in s.notificationDisabledEvents) return
        runCatching { showPlatformNotification(id, title, body, hostId) }
    }
}

/** 平台实现：Android NotificationManager / iOS UNUserNotificationCenter / 桌面 no-op。 */
expect fun showPlatformNotification(id: Int, title: String, body: String, hostId: String?)

/** 打开系统通知设置页（设置页「通知」入口）。 */
expect fun openNotificationSettings()

/** 请求通知权限（Android 13+；打开通知开关时调用）。 */
expect fun requestNotificationPermission()
