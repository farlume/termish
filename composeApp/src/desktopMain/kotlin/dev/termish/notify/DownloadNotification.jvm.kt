package dev.termish.notify

/** 桌面下载走文件管理器（JFileChooser），snackbar 已提示，无需系统通知。 */
actual fun showDownloadDoneNotification(
    title: String,
    body: String,
    openUri: String?,
) {}
