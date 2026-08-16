package dev.mssh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.mssh.data.ConnectionMode
import dev.mssh.data.Host
import dev.mssh.ssh.SftpSession
import dev.mssh.generated.resources.Res
import dev.mssh.generated.resources.host_centos
import dev.mssh.generated.resources.host_linux
import dev.mssh.generated.resources.host_mac
import dev.mssh.generated.resources.host_ubuntu
import dev.mssh.generated.resources.host_windows
import dev.mssh.util.monospaceFontFamily
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** 首页卡片展示的会话（终端或 SFTP）。 */
sealed interface HostSessionItem {
    val hostId: String
    val isActive: Boolean
    val isConnecting: Boolean

    data class Terminal(val controller: TerminalController) : HostSessionItem {
        override val hostId: String get() = controller.host.id
        override val isActive: Boolean get() = isActiveStatus(controller.status)
        override val isConnecting: Boolean
            get() = controller.status == ConnStatus.CONNECTING || controller.status == ConnStatus.AUTH
    }

    data class Sftp(val host: Host, val session: SftpSession) : HostSessionItem {
        override val hostId: String get() = host.id
        override val isActive: Boolean get() = true
        override val isConnecting: Boolean get() = false
    }
}

/** 活跃会话判定：连接中/认证中/已连接都算（与连接页状态点同源）。 */
internal fun isActiveStatus(status: ConnStatus): Boolean =
    status == ConnStatus.CONNECTED || status == ConnStatus.CONNECTING || status == ConnStatus.AUTH

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    hosts: List<Host>,
    hostSessions: Map<String, List<HostSessionItem>>,
    onAdd: () -> Unit,
    onEdit: (Host) -> Unit,
    onConnect: (Host) -> Unit,
    onConnectBatch: (List<Host>) -> Unit,
    onDisconnect: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession) -> Unit,
    onCloseAllSessions: (Host) -> Unit,
) {
    val s = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    val filtered = hosts
        .filter { h ->
            query.isBlank() ||
                h.name.contains(query, ignoreCase = true) ||
                h.hostname.contains(query, ignoreCase = true) ||
                h.username.contains(query, ignoreCase = true) ||
                h.system.contains(query, ignoreCase = true) ||
                h.tags.any { it.contains(query, ignoreCase = true) }
        }
        .sortedWith(compareByDescending<Host> { it.lastConnectedAt }.thenBy { it.name.lowercase() })

    // 批处理（多选）模式：头像点击 / 长按卡片进入
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Host>() }

    fun enterSelection(host: Host) {
        selected.clear()
        selected[host.id] = host
        selectionMode = true
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    // 多选模式下系统返回键先退出批处理，而不是退到桌面
    PlatformBackHandler(enabled = selectionMode, onBack = { exitSelection() })

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionHeader(
                    count = selected.size,
                    onClose = { exitSelection() },
                    onEdit = {
                        selected.values.firstOrNull()?.let(onEdit)
                        exitSelection()
                    },
                    onSelectAll = { filtered.forEach { selected[it.id] = it } },
                    onConnect = {
                        onConnectBatch(selected.values.toList())
                        exitSelection()
                    },
                    onDisconnect = {
                        selected.values.forEach(onDisconnect)
                        exitSelection()
                    },
                    onRemove = {
                        selected.values.forEach(onDelete)
                        exitSelection()
                    },
                )
            } else {
                MsshLargeHeader(title = "MSSH")
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                ) { Icon(Icons.Default.Add, s.hostsAdd) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text(s.hostsSearch) },
                singleLine = true,
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (hosts.isEmpty()) s.hostsEmpty else s.hostsNoMatch,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { host ->
                        val isSelected = host.id in selected
                        val sessions = hostSessions[host.id].orEmpty()
                        HostCard(
                            host = host,
                            sessions = sessions,
                            active = sessions.any { it.isActive },
                            selectionMode = selectionMode,
                            selected = isSelected,
                            onCardClick = {
                                if (selectionMode) {
                                    if (isSelected) selected.remove(host.id) else selected[host.id] = host
                                } else {
                                    // 有活跃会话直接进入第一个，没有则新建
                                    when (val active = sessions.firstOrNull { it.isActive }) {
                                        is HostSessionItem.Terminal -> onOpenSession(active.controller)
                                        is HostSessionItem.Sftp -> onOpenSftp(active.host, active.session)
                                        null -> onConnect(host)
                                    }
                                }
                            },
                            onAvatarClick = {
                                if (selectionMode) {
                                    if (isSelected) selected.remove(host.id) else selected[host.id] = host
                                } else {
                                    enterSelection(host)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) enterSelection(host)
                            },
                            onNewSession = { onConnect(host) },
                            onOpenSession = onOpenSession,
                            onOpenSftp = onOpenSftp,
                            onCloseAllSessions = { onCloseAllSessions(host) },
                        )
                    }
                }
            }
        }
    }
}

/** 批处理模式页头：左侧关闭，中间「x selected」，右侧编辑 + 三点菜单。 */
@Composable
private fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onSelectAll: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalAppStrings.current
    var moreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = s.navBack)
            }
            Text(
                s.hostsSelectedCount(count),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // 编辑：仅单选时可用（编辑当前选中的主机）
            IconButton(onClick = onEdit, enabled = count == 1) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = s.hostsEdit,
                    tint = if (count == 1) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = s.navMore)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(s.hostsSelectAll) },
                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                        onClick = {
                            moreOpen = false
                            onSelectAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsConnect) },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = {
                            moreOpen = false
                            onConnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsDisconnect) },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                        onClick = {
                            moreOpen = false
                            onDisconnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsRemove, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            moreOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 主机卡片（Termius 风格）：系统头像 + 两行信息。
 * - 行 1：主机 IP/地址；行 2：Active 或「协议, 用户名, 系统」
 * - 点击卡片连接；长按卡片或点击头像进入批处理模式
 * - 批处理模式下点击切换选中，选中卡片高亮主色边框
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: Host,
    sessions: List<HostSessionItem>,
    active: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onCardClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession) -> Unit,
    onCloseAllSessions: () -> Unit,
) {
    val s = LocalAppStrings.current
    val sys = host.system.ifBlank { host.hostname }
    val connecting = sessions.any { it.isConnecting }
    // 第一行：alias（名称）优先，为空时回退主机地址
    val title = host.name.ifBlank { host.hostname }
    val activeCount = sessions.count { it.isActive }
    val disconnectedCount = sessions.size - activeCount
    // 有会话：统计文字分段着色（已连接绿色、已断开灰色）；无会话显示连接详情
    val sessionStats = sessions.isNotEmpty()
    val detail = if (!sessionStats) {
        val mode = if (host.connectionMode == ConnectionMode.MOSH) s.hostsModeMosh else s.hostsModeSsh
        buildString {
            append(mode)
            append(", ")
            append(host.username)
            if (host.system.isNotBlank()) {
                append(", ")
                append(host.system)
            }
        }
    } else {
        ""
    }
    val statsText = if (sessionStats) {
        buildAnnotatedString {
            if (activeCount > 0) {
                withStyle(SpanStyle(color = Color(0xFF34C759))) {
                    append("$activeCount ${s.hostsConnected}")
                }
            }
            if (disconnectedCount > 0) {
                if (activeCount > 0) append(", ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("$disconnectedCount ${s.connStatusClosed}")
                }
            }
        }
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onCardClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(14.dp),
        // 跟随主题：浅色=白、暗黑=深色；批处理选中项用主色边框高亮
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 系统头像（圆角正方形 + 白色图标）：点击进入/切换批处理选择
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(systemColor(sys))
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center,
            ) {
                if (connecting) {
                    // 连接中：头像显示转圈，连接完成自动跳转终端页
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    val svg = systemSvg(sys)
                    if (svg != null) {
                        Icon(
                            painterResource(svg),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            systemIcon(sys),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statsText ?: AnnotatedString(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sessionStats) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectionMode) {
                // 选中状态标记：勾选圆
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (sessions.isNotEmpty()) {
                // 全部会话数 + 下拉：新建连接 / 重入会话（含断开，带状态标注）/ 全部关闭
                SessionCountMenu(
                    sessions = sessions,
                    onConnect = onNewSession,
                    onOpenTerminal = onOpenSession,
                    onOpenSftp = onOpenSftp,
                    onCloseAll = onCloseAllSessions,
                )
            }
        }
    }
}

/** 卡片右侧会话下拉：显示会话数量，展开可新建连接、重入会话或全部关闭。 */
@Composable
private fun SessionCountMenu(
    sessions: List<HostSessionItem>,
    onConnect: () -> Unit,
    onOpenTerminal: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession) -> Unit,
    onCloseAll: () -> Unit,
) {
    val s = LocalAppStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sessions.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(s.hostsConnect) },
                onClick = {
                    open = false
                    onConnect()
                },
            )
            HorizontalDivider()
            sessions.forEach { item ->
                val statusLabel = if (item.isActive) s.hostsActive else s.connStatusClosed
                val statusColor = if (item.isActive) Color(0xFF34C759) else Color(0xFF9E9E9E)
                when (item) {
                    is HostSessionItem.Terminal -> DropdownMenuItem(
                        text = {
                            Text(
                                "${item.controller.host.username}@${item.controller.host.hostname}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                            )
                        },
                        onClick = {
                            open = false
                            onOpenTerminal(item.controller)
                        },
                    )
                    is HostSessionItem.Sftp -> DropdownMenuItem(
                        text = {
                            Text(
                                "${item.host.username}@${item.host.hostname}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                            )
                        },
                        onClick = {
                            open = false
                            onOpenSftp(item.host, item.session)
                        },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(s.hostsCloseAll, color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onCloseAll()
                },
            )
        }
    }
}

/** 系统关键词 → 白色 SVG 图标资源（有品牌图标的系统；其余回退 [systemIcon]）。 */
internal fun systemSvg(system: String): DrawableResource? {
    val s = system.lowercase()
    return when {
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Res.drawable.host_mac
        s.contains("windows") || s.contains("win") -> Res.drawable.host_windows
        s.contains("centos") -> Res.drawable.host_centos
        s.contains("ubuntu") -> Res.drawable.host_ubuntu
        s.contains("linux") || s.contains("debian") || s.contains("fedora") || s.contains("arch") ||
            s.contains("alpine") || s.contains("kali") || s.contains("redhat") ||
            s.contains("raspbian") || s.contains("opensuse") || s.contains("manjaro") ||
            s.contains("gentoo") || s.contains("rocky") || s.contains("alma") ||
            s.contains("mint") || s.contains("nixos") || s.contains("pop") || s.contains("elementary") ||
            s.contains("suse") -> Res.drawable.host_linux
        else -> null
    }
}

/** 系统关键词 → 头像图标（Material 兜底：无 SVG 的系统如 android/ios/bsd/未知）。 */
internal fun systemIcon(system: String): ImageVector {
    val s = system.lowercase()
    return when {
        s.contains("android") -> Icons.Filled.Android
        s.contains("ios") || s.contains("iphone") -> Icons.Filled.PhoneIphone
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Icons.Filled.LaptopMac
        s.contains("windows") || s.contains("win") -> Icons.Filled.DesktopWindows
        s.contains("freebsd") || s.contains("bsd") -> Icons.Filled.Memory
        s.contains("linux") || s.contains("ubuntu") || s.contains("debian") ||
            s.contains("centos") || s.contains("fedora") || s.contains("arch") ||
            s.contains("alpine") || s.contains("kali") || s.contains("redhat") ||
            s.contains("raspbian") -> Icons.Filled.Terminal
        else -> Icons.Filled.Dns
    }
}

/**
 * 系统关键词 → 头像背景色（纯色）。识别顺序：
 * 先看主机「系统」字段，为空时用主机名兜底（如 mac.example.com → macOS）。
 * mac 黑、ubuntu 品牌红，其余按品牌色判断。
 */
internal fun systemColor(system: String): Color {
    val s = system.lowercase()
    return when {
        s.contains("android") -> Color(0xFF3DDC84)
        s.contains("ios") || s.contains("iphone") -> Color(0xFF0A84FF)
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Color.Black
        s.contains("windows") || s.contains("win") -> Color(0xFF0078D4)
        s.contains("freebsd") || s.contains("bsd") -> Color(0xFFD6242C)
        s.contains("ubuntu") -> Color(0xFFE95420)
        s.contains("centos") -> Color(0xFF2E6DA4)
        s.contains("linux") || s.contains("debian") ||
            s.contains("fedora") || s.contains("arch") ||
            s.contains("alpine") || s.contains("kali") || s.contains("redhat") ||
            s.contains("raspbian") -> Color(0xFF475569)
        else -> Color(0xFF83838C)
    }
}
