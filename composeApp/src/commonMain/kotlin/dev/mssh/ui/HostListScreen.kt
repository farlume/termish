package dev.mssh.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mssh.data.ConnectionMode
import dev.mssh.data.Host

/** 活跃会话判定：连接中/认证中/已连接都算（与连接页状态点同源）。 */
internal fun isActiveStatus(status: ConnStatus): Boolean =
    status == ConnStatus.CONNECTED || status == ConnStatus.CONNECTING || status == ConnStatus.AUTH

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    hosts: List<Host>,
    activeHostIds: Set<String>,
    onAdd: () -> Unit,
    onEdit: (Host) -> Unit,
    onConnect: (Host) -> Unit,
    onDelete: (Host) -> Unit,
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

    Scaffold(
        topBar = {
            MsshLargeHeader(title = "MSSH")
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Default.Add, s.hostsAdd) }
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
                        HostCard(
                            host = host,
                            active = host.id in activeHostIds,
                            onConnect = { onConnect(host) },
                            onEdit = { onEdit(host) },
                            onDelete = { onDelete(host) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 主机卡片（Termius 风格）：系统头像 + 两行信息。
 * - 行 1：主机 IP/地址
 * - 行 2：有活跃会话 → 绿色 Active；否则「协议, 用户名, 系统」
 * - 点击卡片连接；长按卡片弹出编辑/删除；点击头像直接编辑
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: Host,
    active: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalAppStrings.current
    var menuOpen by remember { mutableStateOf(false) }

    val address = if (host.port != 22) "${host.hostname}:${host.port}" else host.hostname
    val detail = if (active) {
        s.hostsActive
    } else {
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
    }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onConnect,
                    onLongClick = { menuOpen = true },
                ),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE8E8ED)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 系统头像（渐变圆 + 白色图标）：点击直接进入编辑
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(systemGradient(host.system))
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        systemIcon(host.system),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        address,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1C1E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) {
                            Color(0xFF34C759)
                        } else {
                            Color(0xFF6E6E73)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(s.hostsEdit) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(s.hostsDelete, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** 系统关键词 → 头像图标（Material 图标，按用户填写的 system 文本映射）。 */
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

/** 系统关键词 → 头像渐变底色（柔和品牌色，未知用中性灰）。 */
internal fun systemGradient(system: String): Brush {
    val s = system.lowercase()
    val (from, to) = when {
        s.contains("android") -> Color(0xFF7BE0A8) to Color(0xFF25B573)
        s.contains("ios") || s.contains("iphone") -> Color(0xFF66AFFF) to Color(0xFF0A84FF)
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Color(0xFFB9B9C2) to Color(0xFF70707A)
        s.contains("windows") || s.contains("win") -> Color(0xFF5FA8E8) to Color(0xFF0078D4)
        s.contains("freebsd") || s.contains("bsd") -> Color(0xFFF08A8A) to Color(0xFFD6242C)
        s.contains("linux") || s.contains("ubuntu") || s.contains("debian") ||
            s.contains("centos") || s.contains("fedora") || s.contains("arch") ||
            s.contains("alpine") || s.contains("kali") || s.contains("redhat") ||
            s.contains("raspbian") -> Color(0xFFFFA05A) to Color(0xFFE85D2A)
        else -> Color(0xFFBDBDC4) to Color(0xFF83838C)
    }
    return Brush.linearGradient(listOf(from, to))
}
