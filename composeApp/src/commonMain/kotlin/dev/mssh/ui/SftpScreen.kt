package dev.mssh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mssh.data.Host
import dev.mssh.ssh.SftpEntry
import dev.mssh.ssh.SftpSession
import dev.mssh.generated.resources.Res
import dev.mssh.generated.resources.folder
import dev.mssh.util.monospaceFontFamily
import dev.mssh.util.ioDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource

/** SFTP 排序方式。 */
private enum class SftpSort { NAME, DATE, SIZE, KIND }

@Composable
fun SftpContent(
    host: Host,
    session: SftpSession,
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SftpEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SftpSort.NAME) }
    var showHidden by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var newFolderDialog by remember { mutableStateOf(false) }

    fun joinPath(base: String, name: String): String =
        if (base == "/") "/$name" else "$base/$name"

    suspend fun reload() {
        entries = null
        loadError = null
        try {
            entries = withContext(ioDispatcher()) { session.list(path) }
        } catch (e: Exception) {
            loadError = e.message
            entries = emptyList()
        }
    }

    val pickFile = rememberFilePicker { name, bytes ->
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    session.upload(joinPath(path, name), bytes)
                }
                snackbar.showSnackbar(s.sftpUploaded)
                reload()
            } catch (e: Exception) {
                snackbar.showSnackbar(s.sftpLoadFailed(e.message ?: "upload"))
            }
        }
    }

    // 首次进入：优先用户主目录，失败回退根目录
    LaunchedEffect(Unit) {
        val home = "/home/${host.username}"
        path = withContext(ioDispatcher()) {
            if (runCatching { session.list(home) }.isSuccess) home else "/"
        }
    }
    LaunchedEffect(path, sort, showHidden) {
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
    var showParents by remember { mutableStateOf(false) }
    val visible = (entries ?: emptyList())
        .filter { showHidden || !it.isHidden }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
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
            // 精简头部：无边框、小字体、图标用终端前景色（深色背景下可见）
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 搜索模式：X + 圆角搜索框，横向展开动画
                AnimatedVisibility(
                    visible = searching,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = s.navBack,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(s.sftpSearch) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
                // 普通模式：面包屑 + Upload + 菜单 + 搜索 icon
                AnimatedVisibility(
                    visible = !searching,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.weight(1f),
                ) {
                    // 整组放同一行：面包屑 + 上传 + 菜单 + 搜索（AnimatedVisibility 内容非 RowScope，
                    // 多个并列子项会被垂直堆叠，必须包一个 Row）
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 可点击面包屑：每段跳转对应目录；… 下拉父级目录列表
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
                                                        path = pathForBreadcrumb(displayParts.indexOf(parent))
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
                                            .clickable { path = pathForBreadcrumb(displayParts.indexOf(crumb)) }
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
                                scope.launch { snackbar.showSnackbar("TODO: ${s.sftpChangeDownload}") }
                            },
                        )
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = s.sftpSearch, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            when {
                entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.sftpConnecting, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.sftpLoadFailed(loadError!!), color = MaterialTheme.colorScheme.error)
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
                                        path = path.substringBeforeLast('/', "/").ifBlank { "/" }
                                    },
                                )
                            }
                        }
                        items(visible, key = { it.name }) { entry ->
                            SftpRowItem(
                                name = entry.name,
                                permissions = entry.permissions,
                                time = formatTime(entry.modifiedAt),
                                isDirectory = entry.isDirectory,
                                onClick = {
                                    if (entry.isDirectory) {
                                        path = joinPath(path, entry.name)
                                    }
                                },
                            )
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
                scope.launch {
                    try {
                        withContext(ioDispatcher()) {
                            session.mkdir(joinPath(path, name))
                        }
                        reload()
                    } catch (e: Exception) {
                        snackbar.showSnackbar(s.sftpLoadFailed(e.message ?: "mkdir"))
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

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${dt.year}-${p(dt.monthNumber)}-${p(dt.dayOfMonth)} ${p(dt.hour)}:${p(dt.minute)}"
}
