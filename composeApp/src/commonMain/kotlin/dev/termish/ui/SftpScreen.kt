package dev.termish.ui

import dev.termish.util.TermLog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termish.data.Host
import dev.termish.ssh.SftpEntry
import dev.termish.ssh.SftpSession
import dev.termish.ui.theme.StatusColors
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.folder
import dev.termish.notify.showDownloadDoneNotification
import dev.termish.util.monospaceFontFamily
import dev.termish.util.ioDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
internal fun joinPath(base: String, name: String): String =
    if (base == "/") "/$name" else "$base/$name"

/**
 * 递归下载目录：按相对路径写入本地 [DirectorySink]。跳过 `.`/`..`；
 * 单文件下载失败会沿调用栈抛异常（由调用方提示），已写完的文件关闭。
 */
internal fun downloadDir(session: SftpSession, remotePath: String, sink: DirectorySink, rel: String = "") {
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

@Composable
fun SftpContent(
    host: Host,
    session: SftpSession?,
    state: SftpUiState,
    onBack: () -> Unit,
    /** 断线重连（由 AppRoot 重建会话并替换当前 tab）。 */
    onReconnect: () -> Unit,
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
        val results = withContext(ioDispatcher()) {
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

    val pickFile = rememberFilePicker { name, size, readChunk ->
        val sc = session ?: return@rememberFilePicker
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    var lastEmitted = 0L
                    sc.upload(
                        joinPath(path, name),
                        size,
                        onProgress = { sent, total ->
                            // 节流同下载：每 1%（或 64KB，total 未知时）更新一次
                            val step = if (total > 0) (total / 100).coerceAtLeast(1) else 64L * 1024
                            if (sent - lastEmitted >= step || (total > 0 && sent >= total)) {
                                lastEmitted = sent
                                scope.launch(Dispatchers.Main) {
                                    downloadProgress = DownloadProgress(name, sent, total)
                                }
                            }
                        },
                    ) { readChunk() }
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
    val savePicker = rememberFileSaver { _, sink ->
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

    // 选择保存目录后递归下载当前目录；取消保存则不回调
    val saveDir = rememberDirectorySaver { _, sink ->
        val target = pendingDownloadDir
        if (target == null) {
            // 没有待下载目录（防御路径）：直接放弃，不调 sink.close()——
            // iOS 的 close 会弹导出面板，空目录也会弹
            return@rememberDirectorySaver
        }
        val sc = session ?: return@rememberDirectorySaver
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    downloadDir(sc, target, sink)
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
            path = withContext(ioDispatcher()) {
                val home = runCatching { session.home() }.getOrNull()?.let { raw ->
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
    val breadcrumbs = if (displayParts.size > 2) {
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
    val visible = (entries ?: emptyList())
        .filter { showHidden || !it.isHidden }
        .filter { matchesQuery(it.name, query) }
        .let { list ->
            when (sort) {
                SftpSort.NAME -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                SftpSort.DATE -> list.sortedWith(compareByDescending { it.modifiedAt })
                SftpSort.SIZE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
                SftpSort.KIND -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.substringAfterLast('.').lowercase() }))
            }
        }

    // SFTP 是独立页面类型的 tab：主题随应用（浅色页面），不随终端主题
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // 断线 banner：样式与终端页对齐（红色=断开 + 重连按钮 / 琥珀=重连中）
            val bannerText = when {
                state.reconnecting -> s.sftpReconnecting
                state.disconnected || loadError != null -> s.sftpDisconnected
                else -> null
            }
            if (bannerText != null) {
                val bannerColor = if (state.reconnecting) StatusColors.Warning else StatusColors.Error
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
                                    modifier = Modifier
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
                                color = if (i == breadcrumbs.lastIndex) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
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
                TextButton(onClick = { pickFile() }) {
                    Icon(Icons.Filled.Upload, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(4.dp))
                    Text(s.sftpUpload, color = MaterialTheme.colorScheme.onSurface)
                }
                SftpMoreMenu(
                    sort = sort,
                    showHidden = showHidden,
                    tint = MaterialTheme.colorScheme.onSurface,
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
                    modifier = Modifier
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
            // 单文件下载进度横幅（跨平台：iOS/Desktop 也显示）
            downloadProgress?.let { dp ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dp.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (dp.total > 0) {
                            Text(
                                "${(dp.loaded * 100 / dp.total)}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                    LinearProgressIndicator(
                        progress = { if (dp.total > 0) dp.loaded.toFloat() / dp.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }
            when {
                entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.sftpConnecting, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 断开/加载失败：主提示在顶部 banner（红色+重连按钮，同终端样式），
                // 内容区只留中性副提示，不重复红色错误文字
                loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.sftpDisconnected, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 递归搜索：输入关键词时展示跨目录结果（文件名 + 相对路径）
                query.isNotBlank() -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                ) {
                    when {
                        searchLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(s.sftpSearching, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        searchResults.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.sftpNoMatch, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(searchResults.orEmpty(), key = { it.fullPath }) { hit ->
                                SftpSearchRow(
                                    hit = hit,
                                    onClick = {
                                        val target = if (hit.isDirectory) hit.fullPath
                                        else hit.fullPath.substringBeforeLast('/', "/").ifBlank { "/" }
                                        query = ""
                                        searching = false
                                        navigateTo(target)
                                    },
                                )
                            }
                        }
                    }
                }
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                ) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (path != "/") {
                            item("..") {
                                SftpRowItem(
                                    name = "..",
                                    permissions = "",
                                    time = "",
                                    isDirectory = true,
                                    onClick = {
                                        navigateTo(path.substringBeforeLast('/', "/").ifBlank { "/" })
                                    },
                                )
                            }
                        }
                        items(visible, key = { it.name }) { entry ->
                            if (entry.isDirectory) {
                                SftpRowItem(
                                    name = entry.name,
                                    permissions = entry.permissions,
                                    time = formatTime(entry.modifiedAt),
                                    isDirectory = true,
                                    onClick = {
                                        navigateTo(joinPath(path, entry.name))
                                    },
                                )
                            } else {
                                Box {
                                    SftpRowItem(
                                        name = entry.name,
                                        permissions = entry.permissions,
                                        time = formatTime(entry.modifiedAt),
                                        isDirectory = false,
                                        onClick = { fileMenu = entry },
                                    )
                                    DropdownMenu(
                                        expanded = fileMenu?.name == entry.name,
                                        onDismissRequest = { fileMenu = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(s.sftpDownload) },
                                            leadingIcon = { Icon(Icons.Filled.Download, null) },
                                            onClick = {
                                                fileMenu = null
                                                startDownload(entry)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(s.sftpCopyPath) },
                                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                            onClick = {
                                                fileMenu = null
                                                clipboard.setText(AnnotatedString(joinPath(path, entry.name)))
                                                scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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

/** 三点菜单：新建文件夹 / 排序 / 下载目录 / 复制路径 / 隐藏文件。 */
@Composable
private fun SftpMoreMenu(
    sort: SftpSort,
    showHidden: Boolean,
    tint: Color,
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

/** 文件/文件夹列表行：icon + 名称 + 权限，右下角时间。 */
@Composable
private fun SftpRowItem(
    name: String,
    permissions: String,
    time: String,
    isDirectory: Boolean,
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
            if (isDirectory) painterResource(Res.drawable.folder)
            else rememberVectorPainter(Icons.AutoMirrored.Filled.InsertDriveFile),
            contentDescription = null,
            tint = if (isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
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
            if (permissions.isNotEmpty()) {
                Text(
                    permissions,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun NewFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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
internal fun matchesQuery(name: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return q.split(Regex("\\s+")).all { part ->
        if (part.any { it == '*' || it == '?' }) globMatch(name, part)
        else name.contains(part, ignoreCase = true)
    }
}

internal fun globMatch(name: String, pattern: String): Boolean {
    val regex = buildString {
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
data class DownloadProgress(val name: String, val loaded: Long, val total: Long)

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
private fun SftpSearchRow(hit: SftpSearchHit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (hit.isDirectory) painterResource(Res.drawable.folder)
            else rememberVectorPainter(Icons.AutoMirrored.Filled.InsertDriveFile),
            contentDescription = null,
            tint = if (hit.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
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
