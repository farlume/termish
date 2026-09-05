package dev.termish.notify

/**
 * 下载完成通知（独立于 [NotificationCenter] 的会话事件通知：**前台也发**，
 * 因为它是「去系统下载目录打开文件」的入口，而非后台事件提醒）。
 *
 * @param openUri Android 为 MediaStore content:// uri（点击用默认应用打开）；
 *  iOS 传 null（下载走 Files 转存，无需打开动作）。
 */
expect fun showDownloadDoneNotification(
    title: String,
    body: String,
    openUri: String?,
)
