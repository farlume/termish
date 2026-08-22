package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.termish.data.Host
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.folder
import dev.termish.notify.showDownloadDoneNotification
import dev.termish.ssh.SftpEntry
import dev.termish.ssh.SftpSession
import dev.termish.ui.theme.StatusColors
import dev.termish.util.TermLog
import dev.termish.util.decodeImage
import dev.termish.util.ioDispatcher
import dev.termish.util.monospaceFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.painterResource

/** SFTP 排序方式。 */
enum class SftpSort { NAME, DATE, SIZE, KIND }

/**
 * SFTP 浏览状态：跟随会话条目存活（SessionManager 持有），
 * 切 tab / 离开终端页再回来不重置路径与列表。
 */
class SftpUiState {
    var path by mutableStateOf("")
    var entries by mutableStateOf<List<SftpEntry>?>(null)
    var loadError by mutableStateOf<String?>(null)

    /** 底层连接已断开（onClosed 主动推送）：banner 立即显示，可手动重连。
     *  此前断开无感知（onClosed 空实现），只能靠切 tab 重组后 reload 失败才暴露。 */
    var disconnected by mutableStateOf(false)

    /** 断线重连中：顶部显示琥珀色「重新连接中…」banner（同终端重连样式）。 */
    var reconnecting by mutableStateOf(false)

    /** 断线自动重连是否已尝试：跨重组保留，整个会话生命周期只自动一次（防循环）。 */
    var autoReconnectAttempted by mutableStateOf(false)
    var sort by mutableStateOf(SftpSort.NAME)
    var showHidden by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var newFolderDialog by mutableStateOf(false)

    /** 正在显示操作菜单的文件条目（仅文件；目录点击直接进入）。 */
    var fileMenu by mutableStateOf<SftpEntry?>(null)

    /** 多选模式：选中的条目名集合（非空 = 多选模式，列表行切换勾选）。 */
    val selection = mutableStateListOf<String>()

    /** 收藏目录路径集合（快捷跳转）。 */
    val favorites = mutableStateListOf<String>()

    /** 重命名对话框目标（null = 关闭）。 */
    var renameTarget by mutableStateOf<SftpEntry?>(null)

    /** 删除确认目标列表（null = 关闭）。 */
    var deleteTargets by mutableStateOf<List<SftpEntry>?>(null)

    /** 多选批量下载的待下载文件路径（DirectorySaver 回调异步消费）。 */
    var pendingDownloadMulti by mutableStateOf<List<String>?>(null)

    /** 下拉刷新中。 */
    var refreshing by mutableStateOf(false)

    /** 正在预览的文件条目（null = 预览面板关闭）。 */
    var previewEntry by mutableStateOf<SftpEntry?>(null)

    /** 预览加载中。 */
    var previewLoading by mutableStateOf(false)

    /** 预览文本（加载完成；null = 未就绪）。 */
    var previewText by mutableStateOf<String?>(null)

    /** 图片预览位图（图片类文件；null = 未就绪/非图片）。 */
    var previewImage by mutableStateOf<ImageBitmap?>(null)

    /** 预览被截断（文件超过读取上限，只显示前一段）。 */
    var previewTruncated by mutableStateOf(false)

    /** 预览失败原因（null = 无错误）。 */
    var previewError by mutableStateOf<String?>(null)

    /** 等待用户选择保存位置后开始下载的文件（保存回调异步，不能依赖 fileMenu）。 */
    var pendingDownload by mutableStateOf<SftpEntry?>(null)

    /** 面包屑 "…" 的下拉父级菜单开关。 */
    var showParents by mutableStateOf(false)

    /**
     * 目录浏览历史栈：进入目录/面包屑跳转时压入旧路径，
     * 返回键弹栈回到上一个浏览位置（而非机械的父目录）。
     */
    val history = mutableStateListOf<String>()
}

/** 远端路径拼接：根目录下不产生双斜杠。 */
internal fun joinPath(
    base: String,
    name: String,
): String = if (base == "/") "/$name" else "$base/$name"

/** DATE 排序的时间分组桶：今天 / 本周 / 更早（时间未知归更早）。 */
internal fun dateBucket(
    modifiedAt: Long,
    s: AppStrings,
): String {
    if (modifiedAt <= 0) return s.sftpExt.groupEarlier
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    val date = Instant.fromEpochMilliseconds(modifiedAt).toLocalDateTime(tz).date
    return when {
        date == today -> s.sftpExt.groupToday
        date >= today.minus(DatePeriod(days = 6)) -> s.sftpExt.groupWeek
        else -> s.sftpExt.groupEarlier
    }
}

/** 单行条目渲染：多选点击/长按进入多选；非多选时目录进入、文件预览。 */
@Composable
private fun EntryRow(
    entry: SftpEntry,
    selecting: Boolean,
    selection: List<String>,
    path: String,
    onToggle: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onPreview: (SftpEntry) -> Unit,
) {
    SftpRowItem(
        name = entry.name,
        // 目录显示权限串；文件显示大小（移动端大小比权限更有用）
        subtitle = if (entry.isDirectory) entry.permissions else formatSize(entry.size),
        time = formatTime(entry.modifiedAt),
        isDirectory = entry.isDirectory,
        selected = entry.name in selection,
        onClick = {
            when {
                selecting -> onToggle(entry.name)
                entry.isDirectory -> onNavigate(joinPath(path, entry.name))
                else -> onPreview(entry)
            }
        },
        onLongClick = { if (!selecting) onEnterSelection(entry.name) },
    )
}

/**
 * 递归下载目录：按相对路径写入本地 [DirectorySink]。跳过 `.`/`..`；
 * 单文件下载失败会沿调用栈抛异常（由调用方提示），已写完的文件关闭。
 */
internal fun downloadDir(
    session: SftpSession,
    remotePath: String,
    sink: DirectorySink,
    rel: String = "",
) {
    for (e in session.list(remotePath)) {
        if (e.name == "." || e.name == "..") continue
        val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
        if (e.isDirectory) {
            downloadDir(session, joinPath(remotePath, e.name), sink, childRel)
        } else {
            val file = sink.openFile(childRel)
            try {
                session.download(joinPath(remotePath, e.name)) { chunk -> file.write(chunk) }
            } finally {
                file.close()
            }
        }
    }
}

/** 批量下载选中的远端文件到本地 sink（目录下载的伴生：多选操作栏用）。 */
internal fun downloadFilesToSink(
    session: SftpSession,
    paths: List<String>,
    sink: DirectorySink,
) {
    paths.forEach { remotePath ->
        val name = remotePath.substringAfterLast('/').ifBlank { "file" }
        val file = sink.openFile(name)
        try {
            session.download(remotePath) { chunk -> file.write(chunk) }
        } finally {
            file.close()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpContent(
    host: Host,
    session: SftpSession?,
    state: SftpUiState,
    onBack: () -> Unit,
    /** 断线重连（由 AppRoot 重建会话并替换当前 tab）。 */
    onReconnect: () -> Unit,
    /** 收藏变更持久化回调（AppRoot 落盘；null = 不持久化）。 */
    onFavoritesChanged: (List<String>) -> Unit = {},
    /** 浏览路径变更回调（AppRoot 即时持久化，崩溃/强杀不丢）。 */
    onPathChanged: (String) -> Unit = {},
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // 全部浏览状态委托给会话级 [SftpUiState]：切 tab 离开组合后仍然保留
    var path by state::path
    var entries by state::entries
    var loadError by state::loadError
    var sort by state::sort
    var showHidden by state::showHidden
    var searching by state::searching
    var query by state::query
    var newFolderDialog by state::newFolderDialog
    var fileMenu by state::fileMenu
    var pendingDownload by state::pendingDownload
    var showParents by state::showParents
    var previewEntry by state::previewEntry
    var previewLoading by state::previewLoading
    var previewText by state::previewText
    var previewImage by state::previewImage
    var previewTruncated by state::previewTruncated
    var previewError by state::previewError

    /** 等待用户选择保存目录后开始递归下载的远端目录。 */
    var pendingDownloadDir by remember { mutableStateOf<String?>(null) }

    /** 单文件下载进度（null = 无下载中）；顶部进度条横幅数据源。 */
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

    /** header 搜索框焦点：展开后自动聚焦弹键盘。 */
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }
    // 递归搜索：输入关键词时遍历当前目录子树，扁平展示结果（文件名 + 相对路径）。
    // 空关键词恢复普通浏览；query/path/session 变化即取消上一次遍历（LaunchedEffect 自动取消 + debounce）。
    var searchLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SftpSearchHit>?>(null) }
    LaunchedEffect(query, path, session) {
        if (query.isBlank()) {
            searchResults = null
            searchLoading = false
            return@LaunchedEffect
        }
        val sc = session
        if (sc == null) {
            searchResults = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        delay(300) // 输入防抖
        val results =
            withContext(ioDispatcher()) {
                runCatching { searchRecursive(sc, path, query) }.getOrElse { emptyList() }
            }
        searchResults = results
        searchLoading = false
    }

    /** 统一目录跳转入口：压入当前路径到浏览历史，再切换目录。 */
    fun navigateTo(newPath: String) {
        if (newPath == path) return
        state.history.add(path)
        path = newPath
        onPathChanged(newPath)
    }

    // 返回键：搜索中先退出搜索；有浏览历史则弹栈回退；否则返回上一页
    PlatformBackHandler(enabled = true) {
        when {
            searching -> {
                searching = false
                query = ""
            }
            state.history.isNotEmpty() -> path = state.history.removeAt(state.history.lastIndex)
            else -> onBack()
        }
    }

    suspend fun reload() {
        // 重连中不碰旧 session：组合时 reload effect 与自动重连 effect 都会跑，
        // 旧连接已死必然失败——跳过等重连完成的新组合（新 session 再 reload）。
        if (state.reconnecting) return
        val s = session ?: return // 进程重启恢复的条目：等自动重连重建后新组合再加载
        entries = null
        loadError = null
        try {
            entries = withContext(ioDispatcher()) { s.list(path) }
        } catch (e: Exception) {
            TermLog.w("sftp") { "list failed $path: ${e.message}" }
            loadError = e.message
            entries = emptyList()
        }
    }

    // 断线重连：上次浏览失败（连接已断）或进程重启恢复的条目（session=null）
    // 时进入 tab 自动重连一次；之后用户可点 banner 上的「重新连接」手动重试。
    // 标记放 uiState：tab 重组（重连成功后替换 session）会重置 remember，
    // 放 uiState 才能保证整个会话生命周期只自动重连一次，防循环。
    LaunchedEffect(Unit) {
        if ((loadError != null || session == null || state.disconnected) && !state.autoReconnectAttempted) {
            state.autoReconnectAttempted = true
            state.reconnecting = true
            onReconnect()
        }
    }

    fun requestReconnect() {
        if (state.reconnecting) return
        state.reconnecting = true
        onReconnect()
    }

    val pickFile =
        rememberFilePicker { picked ->
            val sc = session ?: return@rememberFilePicker
            // 多选：选择器对每个选中文件回调一次，各自开上传协程（并发上传）
            scope.launch {
                try {
                    withContext(ioDispatcher()) {
                        var lastEmitted = 0L
                        sc.upload(
                            joinPath(path, picked.name),
                            picked.size,
                            onProgress = { sent, total ->
                                // 节流同下载：每 1%（或 64KB，total 未知时）更新一次
                                val step = if (total > 0) (total / 100).coerceAtLeast(1) else 64L * 1024
                                if (sent - lastEmitted >= step || (total > 0 && sent >= total)) {
                                    lastEmitted = sent
                                    scope.launch(Dispatchers.Main) {
                                        downloadProgress = DownloadProgress(picked.name, sent, total)
                                    }
                                }
                            },
                        ) { picked.readChunk() }
                    }
                    downloadProgress = null
                    snackbar.showSnackbar(s.sftpUploaded)
                    reload()
                } catch (e: Exception) {
                    downloadProgress = null
                    snackbar.showSnackbar(s.sftpLoadFailed(e.message ?: "upload"))
                }
            }
        }

    // 选择保存位置后流式下载；取消保存则完全不回调（不产生下载流量）
    val savePicker =
        rememberFileSaver { _, sink ->
            val target = pendingDownload ?: return@rememberFileSaver
            val sc = session ?: return@rememberFileSaver
            val remotePath = joinPath(path, target.name)
            scope.launch {
                try {
                    withContext(ioDispatcher()) {
                        var lastEmitted = 0L
                        sc.download(
                            remotePath,
                            onProgress = { loaded, total ->
                                // 节流：每 1%（或 64KB，total 未知时）才更新一次，避免高频重组
                                val step = if (total > 0) (total / 100).coerceAtLeast(1) else 64L * 1024
                                if (loaded - lastEmitted >= step || (total > 0 && loaded >= total)) {
                                    lastEmitted = loaded
                                    scope.launch(Dispatchers.Main) {
                                        downloadProgress = DownloadProgress(target.name, loaded, total)
                                    }
                                }
                            },
                        ) { chunk -> sink.write(chunk) }
                        sink.close()
                    }
                    downloadProgress = null
                    snackbar.showSnackbar(s.sftpDownloaded)
                    showDownloadDoneNotification(s.sftpDownloadComplete, target.name, sink.openUri)
                } catch (e: Exception) {
                    downloadProgress = null
                    try {
                        sink.close()
                    } catch (_: Exception) {
                    }
                    snackbar.showSnackbar(s.sftpDownloadFailed(e.message ?: "download"))
                }
            }
        }

    fun startDownload(entry: SftpEntry) {
        pendingDownload = entry
        savePicker(entry.name)
    }

    /** 文本预览加载：流式读取远端文件（上限 [PREVIEW_MAX_BYTES]）。 */
    fun loadTextPreview(
        sc: SftpSession,
        entry: SftpEntry,
    ) {
        scope.launch {
            try {
                val result =
                    withContext(ioDispatcher()) {
                        readSftpPreview(sc, joinPath(path, entry.name))
                    }
                if (previewEntry !== entry) return@launch // 已关闭或切到别的文件：丢弃结果
                previewText = result.text
                previewTruncated = result.truncated
            } catch (e: SftpPreviewBinaryException) {
                if (previewEntry !== entry) return@launch
                previewError = s.sftpPreviewBinary
            } catch (e: Exception) {
                if (previewEntry !== entry) return@launch
                previewError = s.sftpPreviewFailed(e.message ?: "preview")
            } finally {
                if (previewEntry === entry) previewLoading = false
            }
        }
    }

    /** 预览入口：图片类文件走位图预览，其余走文本预览；结果挂在会话级 uiState。 */
    fun startPreview(entry: SftpEntry) {
        val sc = session ?: return
        previewEntry = entry
        previewText = null
        previewImage = null
        previewError = null
        previewTruncated = false
        previewLoading = true
        if (isImageName(entry.name)) {
            scope.launch {
                try {
                    val bytes =
                        withContext(ioDispatcher()) {
                            readSftpPreviewBytes(sc, joinPath(path, entry.name), PREVIEW_IMAGE_MAX_BYTES)
                        }
                    if (previewEntry !== entry) return@launch // 已关闭或切到别的文件：丢弃
                    val bmp = withContext(ioDispatcher()) { decodeImage(bytes) }
                    if (previewEntry !== entry) return@launch
                    if (bmp == null) {
                        previewError = s.sftpPreviewFailed("decode")
                    } else {
                        previewImage = bmp
                    }
                } catch (e: SftpPreviewTooLargeException) {
                    if (previewEntry !== entry) return@launch
                    previewError = s.sftpPreviewImageTooLarge
                } catch (e: Exception) {
                    if (previewEntry !== entry) return@launch
                    previewError = s.sftpPreviewFailed(e.message ?: "image")
                } finally {
                    if (previewEntry === entry) previewLoading = false
                }
            }
        } else {
            loadTextPreview(sc, entry)
        }
    }

    fun closePreview() {
        previewEntry = null
        previewText = null
        previewImage = null
        previewError = null
        previewTruncated = false
        previewLoading = false
    }

    // 选择保存目录后递归下载当前目录 / 批量下载选中文件；取消保存则不回调
    val saveDir =
        rememberDirectorySaver { _, sink ->
            val dir = pendingDownloadDir
            val files = state.pendingDownloadMulti
            if (dir == null && files == null) {
                // 没有待下载目标（防御路径）：直接放弃，不调 sink.close()——
                // iOS 的 close 会弹导出面板，空目录也会弹
                return@rememberDirectorySaver
            }
            val sc = session ?: return@rememberDirectorySaver
            scope.launch {
                try {
                    withContext(ioDispatcher()) {
                        if (dir != null) {
                            downloadDir(sc, dir, sink)
                        } else {
                            downloadFilesToSink(sc, files!!, sink)
                        }
                    }
                    sink.close()
                    snackbar.showSnackbar(s.sftpDownloaded)
                } catch (e: Exception) {
                    // 失败不调 sink.close()：iOS 只在全部成功时弹导出面板，避免导出半成品目录
                    snackbar.showSnackbar(s.sftpDownloadFailed(e.message ?: "download"))
                }
            }
        }

    fun startDownloadDir() {
        val remotePath = path
        val name = remotePath.substringAfterLast('/').ifBlank { "root" }
        pendingDownloadDir = remotePath
        saveDir(name)
    }

    // 首次进入（path 尚未初始化）：realpath 解析真实用户主目录（~），
    // 失败回退 /home/{user}，再失败回退根目录；切回不重置。
    // key 含 session：恢复条目（null）重连成功后（非 null）重新执行——
    // 否则 path 为空且首次被跳过时永远不解析 home。
    LaunchedEffect(session) {
        if (state.path.isEmpty() && session != null) {
            path =
                withContext(ioDispatcher()) {
                    val home =
                        runCatching { session.home() }
                            .getOrNull()
                            ?.let { raw ->
                                if (raw.length > 1) raw.trimEnd('/') else raw
                            }?.takeIf { it.isNotBlank() }
                    when {
                        home != null -> home
                        runCatching { session.list("/home/${host.username}") }.isSuccess -> "/home/${host.username}"
                        else -> "/"
                    }
                }
        }
    }
    // key 含 session：重连成功后（null → 新对象）必须重新加载——
    // 首次组合的 reload 可能被 reconnecting 跳过，仅靠 path/sort 变化
    // 不会再次触发（否则「一直连接中」，切 tab 才恢复）。
    LaunchedEffect(path, sort, showHidden, session) {
        if (path.isNotEmpty()) reload()
    }

    // 面包屑动态显示当前路径：始终最多两个层级
    // - 1 段：User（根）或 A
    // - 2 段：A > B
    // - 3+ 段：... > B（… 点击下拉全部父级目录）
    val parts = path.split('/').filter { it.isNotBlank() }
    val displayParts = if (parts.firstOrNull() == "home") listOf(s.sftpUser) + parts.drop(1) else parts
    val breadcrumbs =
        if (displayParts.size > 2) {
            listOf("...", displayParts.last())
        } else {
            displayParts.ifEmpty { listOf(s.sftpUser) }
        }
    // 被折叠的父级目录（最后一段之前的所有层级）
    val hiddenParents = if (displayParts.size > 2) displayParts.dropLast(1) else emptyList()

    fun pathForBreadcrumb(index: Int): String {
        val mapped = displayParts.take(index + 1).map { if (it == s.sftpUser) "home" else it }
        return "/" + mapped.joinToString("/")
    }
    val visible =
        (entries ?: emptyList())
            .filter { showHidden || !it.isHidden }
            .filter { matchesQuery(it.name, query) }
            .let { list ->
                when (sort) {
                    SftpSort.NAME -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    SftpSort.DATE -> list.sortedWith(compareByDescending { it.modifiedAt })
                    SftpSort.SIZE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
                    SftpSort.KIND ->
                        list.sortedWith(
                            compareBy({ !it.isDirectory }, { it.name.substringAfterLast('.').lowercase() }),
                        )
                }
            }

    // ---- 多选 / 删除 / 重命名 / 刷新 ----
    fun selecting() = state.selection.isNotEmpty()

    fun enterSelection(name: String) {
        state.selection.clear()
        state.selection.add(name)
    }

    fun toggleSelect(name: String) {
        if (name in state.selection) state.selection.remove(name) else state.selection.add(name)
    }

    fun clearSelection() = state.selection.clear()

    fun selectAllVisible() {
        state.selection.clear()
        visible.forEach { state.selection.add(it.name) }
    }

    /** 删除确认（单文件菜单 / 多选底部栏共用）。 */
    fun confirmDelete(targets: List<SftpEntry>) {
        state.deleteTargets = targets
    }

    /** 执行删除（递归，平台实现内部处理目录）；成功后刷新并退出多选。 */
    fun doDelete(targets: List<SftpEntry>) {
        val sc = session ?: return
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    targets.forEach { sc.delete(joinPath(path, it.name)) }
                }
                clearSelection()
                reload()
                snackbar.showSnackbar(
                    s.sftpExt
                        .deleteConfirm(targets.size)
                        .removeSuffix("?")
                        .removeSuffix("？"),
                )
            } catch (e: Exception) {
                snackbar.showSnackbar(s.sftpExt.delete + " " + (e.message ?: ""))
            }
        }
    }

    /** 执行重命名。 */
    fun doRename(
        entry: SftpEntry,
        newName: String,
    ) {
        val sc = session ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) {
            state.renameTarget = null
            return
        }
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    sc.rename(joinPath(path, entry.name), joinPath(path, trimmed))
                }
                state.renameTarget = null
                reload()
            } catch (e: Exception) {
                snackbar.showSnackbar(s.sftpExt.rename + " " + (e.message ?: ""))
            }
        }
    }

    /** 手动/下拉刷新当前目录。 */
    fun refreshNow() {
        if (state.refreshing) return
        state.refreshing = true
        scope.launch {
            reload()
            state.refreshing = false
        }
    }

    /** 收藏夹对话框开关（MoreMenu 进入）。 */
    var favoritesOpen by remember { mutableStateOf(false) }

    /** 预览面板 ⋮ 操作菜单开关。 */
    var previewMenu by remember { mutableStateOf(false) }

    /** 收藏/取消收藏当前目录（header 星标）；变更持久化。 */
    fun toggleFavorite(p: String) {
        if (p in state.favorites) state.favorites.remove(p) else state.favorites.add(p)
        onFavoritesChanged(state.favorites.toList())
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // 多选顶部选择条：计数 + 全选 + 退出（紧贴 header 上方）
            if (selecting()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.sftpExt.selected(state.selection.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { selectAllVisible() }) {
                        Text(s.sftpExt.selectAll, style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { clearSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            // 断线 banner（红色 + 重连按钮）；重连中改画布居中指示器
            // （与终端页同款，见 ConnectingIndicator）
            val bannerText =
                when {
                    state.reconnecting -> null
                    state.disconnected || loadError != null -> s.sftpDisconnected
                    else -> null
                }
            if (bannerText != null) {
                val bannerColor = StatusColors.Error
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(bannerColor.copy(alpha = 0.12f))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bannerText,
                        color = bannerColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    )
                    if (!state.reconnecting) {
                        TextButton(onClick = { requestReconnect() }) {
                            Text(s.sftpReconnect, color = bannerColor, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            // 精简头部：无边框、小字体、图标用终端前景色（深色背景下可见）
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 面包屑常驻左侧（可点击跳转；… 下拉父级）
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    breadcrumbs.forEachIndexed { i, crumb ->
                        if (crumb == "...") {
                            Box {
                                Text(
                                    "...",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = monospaceFontFamily(),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { showParents = true }
                                            .padding(horizontal = 2.dp),
                                )
                                DropdownMenu(
                                    expanded = showParents,
                                    onDismissRequest = { showParents = false },
                                ) {
                                    hiddenParents.forEach { parent ->
                                        DropdownMenuItem(
                                            text = { Text(parent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = {
                                                Icon(
                                                    painterResource(Res.drawable.folder),
                                                    null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            },
                                            onClick = {
                                                showParents = false
                                                navigateTo(pathForBreadcrumb(displayParts.indexOf(parent)))
                                            },
                                        )
                                    }
                                }
                            }
                            if (i < breadcrumbs.lastIndex) {
                                Text(
                                    " > ",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                crumb,
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monospaceFontFamily(),
                                color =
                                    if (i == breadcrumbs.lastIndex) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { navigateTo(pathForBreadcrumb(displayParts.indexOf(crumb))) }
                                        .padding(horizontal = 2.dp),
                            )
                            if (i < breadcrumbs.lastIndex) {
                                Text(
                                    " > ",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // 收藏当前目录（星标）：收藏夹列表在三点菜单「收藏夹」里查看/跳转
                IconButton(onClick = { toggleFavorite(path) }) {
                    Icon(
                        if (path in state.favorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription =
                            if (path in
                                state.favorites
                            ) {
                                s.sftpExt.favoriteRemove
                            } else {
                                s.sftpExt.favoriteAdd
                            },
                        tint =
                            if (path in state.favorites) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
                TextButton(onClick = { pickFile() }) {
                    Icon(
                        Icons.Filled.Upload,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(s.sftpUpload, color = MaterialTheme.colorScheme.onSurface)
                }
                SftpMoreMenu(
                    sort = sort,
                    showHidden = showHidden,
                    tint = MaterialTheme.colorScheme.onSurface,
                    isFavorite = path in state.favorites,
                    onToggleFavorite = { toggleFavorite(path) },
                    onOpenFavorites = { favoritesOpen = true },
                    onRefresh = { refreshNow() },
                    onSort = { sort = it },
                    onToggleHidden = { showHidden = !showHidden },
                    onNewFolder = { newFolderDialog = true },
                    onCopyPath = {
                        clipboard.setText(AnnotatedString(path))
                        scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                    },
                    onChangeDownload = {
                        startDownloadDir()
                    },
                )
                // 搜索开关：图标切换（搜索 ⇄ 关闭），搜索框在下方垂直展开
                IconButton(
                    onClick = {
                        if (searching) {
                            searching = false
                            query = ""
                        } else {
                            searching = true
                        }
                    },
                ) {
                    Icon(
                        if (searching) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searching) s.navBack else s.sftpSearch,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // 搜索框：与主机/连接页同款——点击 header 图标垂直展开 + 淡入，自动聚焦
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .focusRequester(searchFocusRequester),
                    placeholder = { Text(s.sftpSearch) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = s.navBack)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
            when {
                // 首连/目录加载中（entries 未就绪）：不在列表区画提示，
                // 统一走外层 ConnectingIndicator 胶囊（与终端页同款）
                entries == null -> Box(Modifier.fillMaxSize())
                // 断开/加载失败：主提示在顶部 banner（红色+重连按钮，同终端样式），
                // 内容区只留中性副提示，不重复红色错误文字
                loadError != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.sftpDisconnected, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                // 递归搜索：输入关键词时展示跨目录结果（文件名 + 相对路径）
                query.isNotBlank() ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    ) {
                        when {
                            searchLoading ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            s.sftpSearching,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            searchResults.isNullOrEmpty() ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        s.sftpNoMatch,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            else ->
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(searchResults.orEmpty(), key = { it.fullPath }) { hit ->
                                        SftpSearchRow(
                                            hit = hit,
                                            onClick = {
                                                val target =
                                                    if (hit.isDirectory) {
                                                        hit.fullPath
                                                    } else {
                                                        hit.fullPath.substringBeforeLast('/', "/").ifBlank { "/" }
                                                    }
                                                query = ""
                                                searching = false
                                                navigateTo(target)
                                            },
                                        )
                                    }
                                }
                        }
                    }
                else ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    ) {
                        if (visible.isEmpty()) {
                            // 空态
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        s.sftpExt.empty,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            // 下拉刷新 + 列表（DATE 排序按 今天/本周/更早 分组）
                            PullToRefreshBox(
                                isRefreshing = state.refreshing,
                                onRefresh = { refreshNow() },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    if (path != "/") {
                                        item("..") {
                                            SftpRowItem(
                                                name = "..",
                                                subtitle = "",
                                                time = "",
                                                isDirectory = true,
                                                onClick = {
                                                    if (selecting()) {
                                                        clearSelection()
                                                    } else {
                                                        navigateTo(path.substringBeforeLast('/', "/").ifBlank { "/" })
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    if (sort == SftpSort.DATE && !selecting()) {
                                        // 时间分组头：今天 / 本周 / 更早（未知时间归更早）
                                        val buckets = linkedMapOf<String, MutableList<SftpEntry>>()
                                        visible.forEach { e ->
                                            buckets.getOrPut(dateBucket(e.modifiedAt, s)) { mutableListOf() }.add(e)
                                        }
                                        buckets.forEach { (bucket, list) ->
                                            item(key = "hdr-$bucket") {
                                                Text(
                                                    bucket,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier =
                                                        Modifier.padding(
                                                            start = 14.dp,
                                                            top = 10.dp,
                                                            bottom = 2.dp,
                                                        ),
                                                )
                                            }
                                            items(list, key = { it.name }) { entry ->
                                                EntryRow(
                                                    entry,
                                                    selecting(),
                                                    state.selection,
                                                    path,
                                                    onToggle = { toggleSelect(it) },
                                                    onEnterSelection = { enterSelection(it) },
                                                    onNavigate = { navigateTo(it) },
                                                    onPreview = { startPreview(it) },
                                                )
                                            }
                                        }
                                    } else {
                                        items(visible, key = { it.name }) { entry ->
                                            EntryRow(
                                                entry,
                                                selecting(),
                                                state.selection,
                                                path,
                                                onToggle = { toggleSelect(it) },
                                                onEnterSelection = { enterSelection(it) },
                                                onNavigate = { navigateTo(it) },
                                                onPreview = { startPreview(it) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
        // 多选底部操作栏：下载 / 删除 / 复制路径 / 取消（盖在列表底部）
        if (selecting()) {
            val selEntries = visible.filter { it.name in state.selection }
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = {
                            state.pendingDownloadMulti = selEntries.map { joinPath(path, it.name) }
                            saveDir("selection")
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpDownload)
                    }
                    TextButton(onClick = { confirmDelete(selEntries) }, enabled = selEntries.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpExt.delete)
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(selEntries.joinToString(" ") { joinPath(path, it.name) }))
                            clearSelection()
                            scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpCopyPath)
                    }
                    TextButton(onClick = { clearSelection() }) {
                        Text(s.terminalCancel)
                    }
                }
            }
        }

        // 单文件下载进度：右下角悬浮卡片（与终端上传同款 TransferProgressCard）
        val downloading = downloadProgress
        if (downloading != null) {
            TransferProgressCard(
                title = downloading.name,
                progress = if (downloading.total > 0) downloading.loaded.toFloat() / downloading.total else 0f,
                percent = if (downloading.total > 0) "${downloading.loaded * 100 / downloading.total}%" else "",
                subtitle = s.sftpDownload,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .zIndex(10f),
            )
        }

        // ---- 重命名 / 删除 / 收藏夹 对话框 ----
        state.renameTarget?.let { entry ->
            var newName by remember(entry.name) { mutableStateOf(entry.name) }
            AlertDialog(
                onDismissRequest = { state.renameTarget = null },
                title = { Text(s.sftpExt.renameTitle) },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { doRename(entry, newName) }) { Text(s.terminalConfirm) }
                },
                dismissButton = {
                    TextButton(onClick = { state.renameTarget = null }) { Text(s.terminalCancel) }
                },
            )
        }
        state.deleteTargets?.let { targets ->
            AlertDialog(
                onDismissRequest = { state.deleteTargets = null },
                title = { Text(s.sftpExt.deleteConfirm(targets.size)) },
                text = { Text(s.sftpExt.deleteConfirmBody) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.deleteTargets = null
                            doDelete(targets)
                        },
                    ) { Text(s.terminalConfirm) }
                },
                dismissButton = {
                    TextButton(onClick = { state.deleteTargets = null }) { Text(s.terminalCancel) }
                },
            )
        }
        if (favoritesOpen) {
            AlertDialog(
                onDismissRequest = { favoritesOpen = false },
                title = { Text(s.sftpExt.favoritesTitle) },
                text = {
                    if (state.favorites.isEmpty()) {
                        Text(
                            s.sftpExt.favoritesEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column {
                            state.favorites.toList().forEach { fav ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            favoritesOpen = false
                                            navigateTo(fav)
                                        }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        fav,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    )
                                    IconButton(onClick = {
                                        state.favorites.remove(fav)
                                        onFavoritesChanged(state.favorites.toList())
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { favoritesOpen = false }) { Text(s.terminalCancel) }
                },
            )
        }

        // 文本预览覆盖层：全屏模态盖住列表；标题栏（名称+大小+复制+关闭）+ 等宽文本滚动区。
        // 预览状态挂在会话级 uiState：切 tab 离开再回来，预览面板原样保留。
        previewEntry?.let { entry ->
            val lines = remember(previewText) { previewText.orEmpty().lines() }
            val err = previewError // delegated property 不能 smart cast，取局部值
            // 图片预览：true=适配屏幕；false=原始像素（可滚动）
            var imageFit by remember(entry.name) { mutableStateOf(true) }
            // Markdown：渲染/源码 切换（默认渲染模式）；仅 md/markdown 文件显示
            val isMarkdown = extensionOf(entry.name) in setOf("md", "markdown")
            var mdRender by remember(entry.name) { mutableStateOf(true) }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            rememberVectorPainter(Icons.AutoMirrored.Filled.InsertDriveFile),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 复制全部：预览文本已整段在内存，一键进剪贴板（SelectionContainer
                        // 在 LazyColumn 上只能行内选择，长文复制靠这个按钮兜底）
                        val previewCopyable = previewText // delegated property 不能 smart cast
                        if (previewCopyable != null) {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(previewCopyable))
                                    scope.launch { snackbar.showSnackbar(s.sftpPreviewCopied) }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = s.sftpPreviewCopied,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        // 图片预览：适配 / 原始大小切换（原始大小可滚动查看细节）
                        if (previewImage != null) {
                            IconButton(onClick = { imageFit = !imageFit }) {
                                Icon(
                                    if (imageFit) Icons.Filled.ZoomIn else Icons.Filled.ZoomOut,
                                    contentDescription = s.sftpPreviewZoom,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        // Markdown 渲染 ⇄ 源码 切换
                        if (isMarkdown && previewText != null) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(2.dp),
                            ) {
                                TextButton(
                                    onClick = { mdRender = true },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        "预览",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (mdRender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { mdRender = false },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        "源码",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!mdRender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // 更多操作：下载 / 重命名 / 删除 / 复制路径（文件操作入口，
                        // 列表单击已改为直接预览，操作集中在此 + 多选栏）
                        Box {
                            IconButton(onClick = { previewMenu = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(expanded = previewMenu, onDismissRequest = { previewMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(s.sftpDownload) },
                                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                                    onClick = {
                                        previewMenu = false
                                        startDownload(entry)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.sftpExt.rename) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = {
                                        previewMenu = false
                                        state.renameTarget = entry
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.sftpExt.delete) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = {
                                        previewMenu = false
                                        confirmDelete(listOf(entry))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.sftpCopyPath) },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        previewMenu = false
                                        clipboard.setText(AnnotatedString(joinPath(path, entry.name)))
                                        scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { closePreview() }) {
                            Icon(Icons.Filled.Close, contentDescription = s.navBack)
                        }
                    }
                    HorizontalDivider()
                    when {
                        previewLoading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            }
                        // 图片预览：黑底居中，适配模式整图可见，原始模式可滚动查看细节
                        previewImage != null ->
                            Box(
                                Modifier.fillMaxSize().background(Color.Black),
                            ) {
                                if (imageFit) {
                                    Image(
                                        previewImage!!,
                                        contentDescription = entry.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    val hScroll = rememberScrollState()
                                    val vScroll = rememberScrollState()
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .verticalScroll(vScroll)
                                            .horizontalScroll(hScroll),
                                    ) {
                                        Image(
                                            previewImage!!,
                                            contentDescription = entry.name,
                                            modifier = Modifier.size(previewImage!!.width.dp, previewImage!!.height.dp),
                                            contentScale = ContentScale.FillBounds,
                                        )
                                    }
                                }
                            }
                        err != null ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    err,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        else ->
                            Column(Modifier.fillMaxSize()) {
                                if (previewTruncated) {
                                    Text(
                                        s.sftpPreviewTruncated(formatSize(PREVIEW_MAX_BYTES.toLong())),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f))
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                                val srcText = previewText.orEmpty()
                                if (isMarkdown && mdRender) {
                                    // 渲染模式：Markdown 效果展示（可复制），纵向滚动
                                    Column(
                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                renderMarkdown(
                                                    srcText,
                                                    baseFontSize = 14.sp,
                                                    codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                                    linkColor = MaterialTheme.colorScheme.primary,
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            )
                                        }
                                    }
                                } else {
                                    // 源码模式：逐行等宽（Markdown 与其他文本文件一致）
                                    LazyColumn(
                                        Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        items(lines.size) { i ->
                                            SelectionContainer {
                                                Text(
                                                    lines[i],
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = monospaceFontFamily(),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }

        // SnackbarHost 放预览层之后（z 序最高）：预览模式下复制成功等提示不被面板遮住
        // 多选底部操作栏：下载 / 删除 / 复制路径 / 取消（盖在列表底部）
        if (selecting()) {
            val selEntries = visible.filter { it.name in state.selection }
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = {
                            state.pendingDownloadMulti = selEntries.map { joinPath(path, it.name) }
                            saveDir("selection")
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpDownload)
                    }
                    TextButton(onClick = { confirmDelete(selEntries) }, enabled = selEntries.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpExt.delete)
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(selEntries.joinToString(" ") { joinPath(path, it.name) }))
                            clearSelection()
                            scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpCopyPath)
                    }
                    TextButton(onClick = { clearSelection() }) {
                        Text(s.terminalCancel)
                    }
                }
            }
        }

        TermishSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))

        // 首连（列表未加载）与断线重连共用同一居中胶囊（终端页同款，淡入淡出）：
        // 同页不再出现裸文本「连接中…」，两页连接态视觉完全统一
        ConnectingIndicator(
            visible = state.reconnecting || entries == null,
            text = if (state.reconnecting) s.sftpReconnecting else s.sftpConnecting,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    if (newFolderDialog) {
        NewFolderDialog(
            onConfirm = { name ->
                newFolderDialog = false
                val sc = session
                if (sc != null) {
                    scope.launch {
                        try {
                            withContext(ioDispatcher()) {
                                sc.mkdir(joinPath(path, name))
                            }
                            reload()
                        } catch (e: Exception) {
                            snackbar.showSnackbar(s.sftpLoadFailed(e.message ?: "mkdir"))
                        }
                    }
                }
            },
            onDismiss = { newFolderDialog = false },
        )
    }
}

/** 三点菜单：新建文件夹 / 排序 / 收藏 / 刷新 / 下载目录 / 复制路径 / 隐藏文件。 */
@Composable
private fun SftpMoreMenu(
    sort: SftpSort,
    showHidden: Boolean,
    tint: Color,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenFavorites: () -> Unit,
    onRefresh: () -> Unit,
    onSort: (SftpSort) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFolder: () -> Unit,
    onCopyPath: () -> Unit,
    onChangeDownload: () -> Unit,
) {
    val s = LocalAppStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = s.navMore, tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(s.sftpNewFolder) },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                onClick = {
                    open = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(if (isFavorite) s.sftpExt.favoriteRemove else s.sftpExt.favoriteAdd) },
                leadingIcon = { Icon(if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder, null) },
                onClick = {
                    open = false
                    onToggleFavorite()
                },
            )
            DropdownMenuItem(
                text = { Text(s.sftpExt.refresh) },
                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = {
                    open = false
                    onRefresh()
                },
            )
            HorizontalDivider()
            SftpSortItem(s.sftpSortName, Icons.Filled.SortByAlpha, sort == SftpSort.NAME) { onSort(SftpSort.NAME) }
            SftpSortItem(s.sftpSortDate, Icons.Filled.Schedule, sort == SftpSort.DATE) { onSort(SftpSort.DATE) }
            SftpSortItem(s.sftpSortSize, Icons.Filled.DataUsage, sort == SftpSort.SIZE) { onSort(SftpSort.SIZE) }
            SftpSortItem(s.sftpSortKind, Icons.Filled.Description, sort == SftpSort.KIND) { onSort(SftpSort.KIND) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(s.sftpChangeDownload) },
                leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = {
                    open = false
                    onChangeDownload()
                },
            )
            DropdownMenuItem(
                text = { Text(s.sftpCopyPath) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = {
                    open = false
                    onCopyPath()
                },
            )
            DropdownMenuItem(
                text = { Text(s.sftpHiddenFiles) },
                leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                trailingIcon = { if (showHidden) Icon(Icons.Filled.Check, null) },
                onClick = {
                    open = false
                    onToggleHidden()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(s.sftpExt.favoritesTitle) },
                leadingIcon = { Icon(Icons.Filled.Star, null) },
                onClick = {
                    open = false
                    onOpenFavorites()
                },
            )
        }
    }
}

@Composable
private fun SftpSortItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, null) },
        onClick = onClick,
    )
}

/** 文件/文件夹列表行：icon + 名称（目录=权限/文件=大小副标题）+ 时间；
 *  支持多选选中态与长按（进入多选）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SftpRowItem(
    name: String,
    subtitle: String,
    time: String,
    isDirectory: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter =
                if (isDirectory) {
                    painterResource(Res.drawable.folder)
                } else {
                    rememberVectorPainter(fileKindIcon(fileKindOf(name)))
                },
            contentDescription = null,
            // 文件夹用主题色（翠绿）；文件用类型色（线性图标小尺寸清晰，色彩保留区分）
            tint =
                if (isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    fileKindColor(fileKindOf(name))
                },
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (time.isNotEmpty()) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.sftpNewFolder) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(s.sftpFolderName) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(s.sftpCreate)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.terminalCancel) } },
    )
}

internal fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${dt.year}-${p(dt.monthNumber)}-${p(dt.dayOfMonth)} ${p(dt.hour)}:${p(dt.minute)}"
}

/**
 * SFTP 文件名匹配：空格分隔多关键词（AND 关系）；含 `*`/`?` 的关键词按通配符匹配，
 * 其余按忽略大小写的包含匹配。例：`*.log`、`conf nginx`、`readme?`。
 */
internal fun matchesQuery(
    name: String,
    query: String,
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return q.split(Regex("\\s+")).all { part ->
        if (part.any { it == '*' || it == '?' }) {
            globMatch(name, part)
        } else {
            name.contains(part, ignoreCase = true)
        }
    }
}

internal fun globMatch(
    name: String,
    pattern: String,
): Boolean {
    val regex =
        buildString {
            append('^')
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }
    return Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(name)
}

/** 递归搜索结果：文件名 + 完整远端路径 + 相对当前目录的路径。 */
data class SftpSearchHit(
    val name: String,
    val fullPath: String,
    val relPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

/** 单文件下载进度（顶部进度条横幅数据源）；total=0 表示服务器未报大小。 */
data class DownloadProgress(
    val name: String,
    val loaded: Long,
    val total: Long,
)

/**
 * 递归搜索：从 [root] 起遍历目录树，返回文件名匹配 [query] 的条目
 * （匹配沿用 [matchesQuery]：多关键词 AND + glob）。深度优先栈迭代；
 * [maxDepth] 限深、[maxResults] 限结果数，防超深目录/海量结果拖垮会话。
 * 单目录 list 失败（权限/断开）跳过该子树继续；每层目录间响应协程取消。
 */
suspend fun searchRecursive(
    session: SftpSession,
    root: String,
    query: String,
    maxDepth: Int = 8,
    maxResults: Int = 500,
): List<SftpSearchHit> {
    val results = ArrayList<SftpSearchHit>()
    val stack = ArrayDeque<Triple<String, String, Int>>()
    stack.addLast(Triple(root, "", 0))
    while (stack.isNotEmpty() && results.size < maxResults) {
        currentCoroutineContext().ensureActive()
        val (dir, rel, depth) = stack.removeLast()
        val entries = runCatching { session.list(dir) }.getOrNull() ?: continue
        for (e in entries) {
            if (results.size >= maxResults) break
            if (e.name == "." || e.name == "..") continue
            val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            val childPath = joinPath(dir, e.name)
            if (matchesQuery(e.name, query)) {
                results.add(SftpSearchHit(e.name, childPath, childRel, e.isDirectory, e.size, e.modifiedAt))
            }
            if (e.isDirectory && depth < maxDepth) {
                stack.addLast(Triple(childPath, childRel, depth + 1))
            }
        }
    }
    return results
}

/** 递归搜索结果行：文件/目录图标 + 名称 + 相对路径（主色标注），右下角时间。 */
@Composable
private fun SftpSearchRow(
    hit: SftpSearchHit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter =
                if (hit.isDirectory) {
                    painterResource(Res.drawable.folder)
                } else {
                    rememberVectorPainter(fileKindIcon(fileKindOf(hit.name)))
                },
            contentDescription = null,
            tint =
                if (hit.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    fileKindColor(fileKindOf(hit.name))
                },
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                hit.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.relPath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hit.modifiedAt > 0) {
            Text(
                formatTime(hit.modifiedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

// ---------- 文件类型识别（列表 icon + 预览分流） ----------

/** SFTP 文件类型：决定列表 icon 与预览方式。 */
enum class SftpFileKind {
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    APK,
    DOC,
    SHEET,
    SLIDES,
    EXEC,
    FONT,
    DB,
    DISK,
    KEY,
    MD,
    TORRENT,
    CONFIG,
    CODE,
    TEXT,
    PDF,
    OTHER,
}

internal fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** 可在线预览的图片格式（平台解码：Android BitmapFactory / iOS UIImage / 桌面 ImageIO；gif 显示首帧）。 */
internal val PREVIEW_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** 图片类文件：走位图预览分支（文本预览的 NUL 检测会误杀图片）。 */
internal fun isImageName(name: String): Boolean = extensionOf(name) in PREVIEW_IMAGE_EXTENSIONS

/** 按扩展名识别文件类型（列表 icon 用）；未知类型归 OTHER。 */
internal fun fileKindOf(name: String): SftpFileKind =
    when (extensionOf(name)) {
        in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "ico", "avif") -> SftpFileKind.IMAGE
        in setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "m2ts") -> SftpFileKind.VIDEO
        in setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "mid", "wma") -> SftpFileKind.AUDIO
        in
        setOf(
            "zip",
            "tar",
            "gz",
            "bz2",
            "xz",
            "7z",
            "rar",
            "tgz",
            "zst",
            "tar.gz",
            "tar.bz2",
            "tar.xz",
        ),
        -> SftpFileKind.ARCHIVE
        in setOf("iso", "img") -> SftpFileKind.DISK
        in setOf("doc", "docx", "odt", "rtf", "pages", "wps") -> SftpFileKind.DOC
        in setOf("xls", "xlsx", "ods", "numbers", "et") -> SftpFileKind.SHEET
        in setOf("ppt", "pptx", "odp", "key", "dps") -> SftpFileKind.SLIDES
        in setOf("exe", "msi", "deb", "rpm", "appimage", "app", "bat", "cmd", "com", "dmg", "pkg") -> SftpFileKind.EXEC
        "apk" -> SftpFileKind.APK
        in setOf("pem", "key", "crt", "cer", "p12", "pfx", "der", "jks") -> SftpFileKind.KEY
        "torrent" -> SftpFileKind.TORRENT
        in
        setOf(
            "env",
            "yml",
            "yaml",
            "toml",
            "ini",
            "conf",
            "properties",
            "cfg",
            "editorconfig",
            "gitconfig",
        ),
        -> SftpFileKind.CONFIG
        in setOf("ttf", "otf", "woff", "woff2", "eot") -> SftpFileKind.FONT
        in setOf("db", "sqlite", "sqlite3", "mdb") -> SftpFileKind.DB
        in
        setOf(
            "kt",
            "kts",
            "java",
            "c",
            "h",
            "cpp",
            "hpp",
            "cc",
            "py",
            "js",
            "ts",
            "tsx",
            "jsx",
            "go",
            "rs",
            "swift",
            "sh",
            "bash",
            "zsh",
            "rb",
            "php",
            "sql",
            "html",
            "css",
            "scss",
            "xml",
            "yml",
            "yaml",
            "json",
            "toml",
            "gradle",
            "properties",
            "ini",
            "conf",
            "cfg",
            "dockerfile",
            "makefile",
            "lock",
            "patch",
            "diff",
            "vue",
            "svelte",
            "dart",
            "lua",
            "pl",
            "r",
        ),
        -> SftpFileKind.CODE
        in setOf("md", "markdown") -> SftpFileKind.MD
        in setOf("txt", "log", "rst", "adoc", "text", "nfo") -> SftpFileKind.TEXT
        "pdf" -> SftpFileKind.PDF
        else -> SftpFileKind.OTHER
    }

/** 文件类型 icon（material-icons-extended 线性图标；tint 由调用方按类型色/主题色给）。 */
@Composable
private fun fileKindIcon(kind: SftpFileKind): ImageVector =
    when (kind) {
        SftpFileKind.IMAGE -> Icons.Filled.Image
        SftpFileKind.VIDEO -> Icons.Filled.VideoFile
        SftpFileKind.AUDIO -> Icons.Filled.MusicNote
        SftpFileKind.ARCHIVE -> Icons.Filled.Archive
        SftpFileKind.APK -> Icons.Filled.Android
        SftpFileKind.DOC -> Icons.Filled.Description
        SftpFileKind.SHEET -> Icons.Filled.TableChart
        SftpFileKind.SLIDES -> Icons.Filled.Slideshow
        SftpFileKind.EXEC -> Icons.Filled.PlayArrow
        SftpFileKind.FONT -> Icons.Filled.TextFields
        SftpFileKind.DB -> Icons.Filled.Storage
        SftpFileKind.DISK -> Icons.Filled.Album
        SftpFileKind.KEY -> Icons.Filled.Key
        SftpFileKind.MD -> Icons.Filled.Notes
        SftpFileKind.TORRENT -> Icons.Filled.Download
        SftpFileKind.CONFIG -> Icons.Filled.Tune
        SftpFileKind.CODE -> Icons.Filled.Code
        SftpFileKind.TEXT -> Icons.Filled.Article
        SftpFileKind.PDF -> Icons.Filled.PictureAsPdf
        SftpFileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

/** 文件类型着色（线性图标 tint；列表行色彩区分，文件夹仍用主题色）。 */
internal fun fileKindColor(kind: SftpFileKind): Color =
    when (kind) {
        SftpFileKind.IMAGE -> Color(0xFF43A047)
        SftpFileKind.VIDEO -> Color(0xFF8E24AA)
        SftpFileKind.AUDIO -> Color(0xFFF57C00)
        SftpFileKind.ARCHIVE -> Color(0xFF6D4C41)
        SftpFileKind.APK -> Color(0xFF00A862)
        SftpFileKind.DOC -> Color(0xFF1E88E5)
        SftpFileKind.SHEET -> Color(0xFF2E7D32)
        SftpFileKind.SLIDES -> Color(0xFFF4511E)
        SftpFileKind.EXEC -> Color(0xFF7E57C2)
        SftpFileKind.FONT -> Color(0xFF00ACC1)
        SftpFileKind.DB -> Color(0xFF3949AB)
        SftpFileKind.DISK -> Color(0xFF546E7A)
        SftpFileKind.KEY -> Color(0xFFE6A23C)
        SftpFileKind.MD -> Color(0xFF9C27B0)
        SftpFileKind.TORRENT -> Color(0xFF00BFA5)
        SftpFileKind.CONFIG -> Color(0xFF90A4AE)
        SftpFileKind.CODE -> Color(0xFF455A64)
        SftpFileKind.TEXT -> Color(0xFF607D8B)
        SftpFileKind.PDF -> Color(0xFFE53935)
        SftpFileKind.OTHER -> Color(0xFF78909C)
    }

// ---------- 文本预览 ----------

/** 预览读取上限：超过即截断（大文件不整读进内存，渲染也不卡）。 */
const val PREVIEW_MAX_BYTES: Int = 512 * 1024

/** 图片预览读取上限：解码需要完整数据，放宽到 8MB（超限提示无法预览而非截断坏图）。 */
const val PREVIEW_IMAGE_MAX_BYTES: Int = 8 * 1024 * 1024

/** 二进制采样区大小：前 4KB 内出现 NUL 字节即判定为二进制。 */
internal const val PREVIEW_BINARY_SAMPLE = 4096

/** 文本预览结果：UTF-8 解码内容 + 是否被截断。 */
data class SftpPreviewResult(
    val text: String,
    val truncated: Boolean,
)

/** 二进制文件（内容含 NUL），无法预览。 */
class SftpPreviewBinaryException : Exception()

/** 超过读取上限（内部信号：中断下载流，结果截断展示）。 */
class SftpPreviewTooLargeException : Exception()

/**
 * 流式读取远端文件前 [maxBytes] 字节（图片预览用：不整读大文件）。
 * 超过上限抛 [SftpPreviewTooLargeException]（图片截断无法解码，调用方提示超限）；
 * 不做 NUL 检测：图片内容必然含 NUL。
 */
suspend fun readSftpPreviewBytes(
    session: SftpSession,
    remotePath: String,
    maxBytes: Int,
): ByteArray =
    withContext(ioDispatcher()) {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        session.download(remotePath) { chunk ->
            val room = maxBytes - total
            if (room <= 0) throw SftpPreviewTooLargeException()
            val n = minOf(chunk.size, room)
            chunks += chunk.copyOfRange(0, n)
            total += n
        }
        val bytes = ByteArray(total)
        var off = 0
        for (c in chunks) {
            c.copyInto(bytes, off)
            off += c.size
        }
        bytes
    }

/**
 * 流式读取远端文件前 [maxBytes] 字节并解码为 UTF-8 文本：
 * - 超过上限立即中断下载（不整读大文件），结果标记截断
 * - 内容前 4KB 含 NUL 字节判定为二进制，抛 [SftpPreviewBinaryException]
 * - 非法 UTF-8 序列按替换字符处理，不会崩
 *
 * 复用 [SftpSession.download] 的分块回调：onChunk 抛异常即中断传输
 * （两平台实现都在 finally 关闭通道），无需额外 close 语义。
 */
suspend fun readSftpPreview(
    session: SftpSession,
    remotePath: String,
    maxBytes: Int = PREVIEW_MAX_BYTES,
): SftpPreviewResult =
    withContext(ioDispatcher()) {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        var truncated = false
        var binary = false
        try {
            session.download(remotePath) { chunk ->
                // 采样区检测 NUL：不依赖扩展名白名单，无扩展名文本（LICENSE/Makefile）也能预览
                if (!binary && total < PREVIEW_BINARY_SAMPLE) {
                    val n = minOf(chunk.size, PREVIEW_BINARY_SAMPLE - total)
                    for (i in 0 until n) {
                        if (chunk[i] == 0.toByte()) {
                            binary = true
                            break
                        }
                    }
                }
                if (binary) throw SftpPreviewBinaryException()
                val room = maxBytes - total
                if (room <= 0) throw SftpPreviewTooLargeException()
                val n = minOf(chunk.size, room)
                chunks += chunk.copyOfRange(0, n)
                total += n
            }
        } catch (e: SftpPreviewTooLargeException) {
            truncated = true
        }
        val bytes = ByteArray(total)
        var off = 0
        for (c in chunks) {
            c.copyInto(bytes, off)
            off += c.size
        }
        // common API：decodeToString 默认 UTF-8，非法序列替换字符（与 JVM toString(Charsets.UTF_8) 一致）
        SftpPreviewResult(bytes.decodeToString(), truncated)
    }

/** 人类可读文件大小（1024 进制，KB 起保留一位小数）：如 512 B / 1.5 KB / 2.3 MB。 */
internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$bytes B" else "${((v * 10).toInt() / 10.0)} ${units[i]}"
}
