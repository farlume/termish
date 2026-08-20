package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.StatusColors
import kotlinx.coroutines.delay

/** 连接页：活跃/历史会话列表（主机页同款卡片 + 搜索），点击重入终端，右侧断开。 */
@Composable
fun ConnectionsScreen(
    sessions: List<HostSessionItem>,
    onOpen: (HostSessionItem) -> Unit,
    onClose: (HostSessionItem) -> Unit,
) {
    val s = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // 搜索展开后等入场动画开始再聚焦，键盘随之弹出
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }
    val filtered = sessions.filter { item ->
            val host = when (item) {
                is HostSessionItem.Terminal -> item.controller.host
                is HostSessionItem.Sftp -> item.host
            }
            query.isBlank() ||
                host.name.contains(query, ignoreCase = true) ||
                host.hostname.contains(query, ignoreCase = true) ||
                host.username.contains(query, ignoreCase = true)
        }

    Scaffold(
        topBar = {
            TermishLargeHeader(
                title = s.appTabConnections,
                actions = {
                    // 无会话时搜索无意义，图标不显示（与原有「有会话才显示搜索框」一致）
                    if (sessions.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                if (searchExpanded) {
                                    searchExpanded = false
                                    query = ""
                                } else {
                                    searchExpanded = true
                                }
                            },
                        ) {
                            Icon(
                                if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (searchExpanded) s.navBack else s.connSearch,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isNotEmpty()) {
                AnimatedVisibility(
                    visible = searchExpanded,
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
                        placeholder = { Text(s.connSearch) },
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
                // 分组标题（与首页「我的主机」同款样式，左距对齐卡片 8dp）
                Text(
                    s.connSectionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (sessions.isEmpty()) s.connEmpty else s.connNoMatch,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = {
                        when (it) {
                            is HostSessionItem.Terminal -> it.controller.sessionId
                            is HostSessionItem.Sftp -> "sftp:${it.host.id}:${it.session.hashCode()}"
                        }
                    }) { item ->
                        val host = when (item) {
                            is HostSessionItem.Terminal -> item.controller.host
                            is HostSessionItem.Sftp -> item.host
                        }
                        val title = when (item) {
                            is HostSessionItem.Terminal -> item.controller.title
                            is HostSessionItem.Sftp -> "${host.username}@${host.hostname}"
                        }
                        val active = item.isActive
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable { onOpen(item) },
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        if (item is HostSessionItem.Sftp) {
                                            "${host.username}@${host.hostname} · SFTP"
                                        } else {
                                            "${host.username}@${host.hostname}:${host.port}"
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    val isConnecting = item is HostSessionItem.Terminal &&
                                        (item.controller.status == ConnStatus.CONNECTING || item.controller.status == ConnStatus.AUTH)
                                    if (isConnecting) {
                                        // 连接中：状态点位置换小 spinner（与首页头像转圈同语义）
                                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                            StatusSpinner(Modifier.size(14.dp))
                                        }
                                    } else {
                                        Box(
                                            Modifier.size(10.dp).clip(CircleShape)
                                                .background(
                                                    if (item is HostSessionItem.Terminal) {
                                                        statusColor(item.controller.status, item.controller.linkLostSeconds)
                                                    } else {
                                                        StatusColors.Connected
                                                    },
                                                ),
                                        )
                                    }
                                },
                                trailingContent = {
                                    IconButton(onClick = { onClose(item) }) {
                                        // 活跃 → 断开/关闭（断链图标）；已断开终端 → 从列表移除
                                        if (active) {
                                            Icon(Icons.Default.LinkOff, contentDescription = s.connDisconnect)
                                        } else {
                                            Icon(Icons.Default.Delete, contentDescription = s.connRemove)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun statusColor(status: ConnStatus, linkLostSeconds: Int = 0): Color = when {
    // 链路失联（会话保持中）：琥珀色，与终端页状态点/banner 同源
    status == ConnStatus.CONNECTED && linkLostSeconds >= LINK_LOST_THRESHOLD_SECONDS -> StatusColors.Warning
    status == ConnStatus.CONNECTED -> StatusColors.Connected
    status == ConnStatus.CONNECTING || status == ConnStatus.AUTH -> StatusColors.Warning
    status == ConnStatus.ERROR -> StatusColors.Error
    else -> StatusColors.Neutral // CLOSED / IDLE
}
