package dev.mssh.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 连接页：活跃/历史会话列表（主机页同款卡片 + 搜索），点击重入终端，右侧断开。 */
@Composable
fun ConnectionsScreen(
    sessions: List<TerminalController>,
    onOpen: (TerminalController) -> Unit,
    onClose: (TerminalController) -> Unit,
) {
    val s = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    val filtered = sessions.filter { c ->
        query.isBlank() ||
            c.title.contains(query, ignoreCase = true) ||
            c.host.name.contains(query, ignoreCase = true) ||
            c.host.hostname.contains(query, ignoreCase = true) ||
            c.host.username.contains(query, ignoreCase = true)
    }

    Scaffold(
        topBar = { MsshLargeHeader(title = s.appTabConnections) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    placeholder = { Text(s.connSearch) },
                    singleLine = true,
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
                    items(filtered, key = { it.host.id }) { controller ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable { onOpen(controller) },
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(controller.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        "${controller.host.username}@${controller.host.hostname}:${controller.host.port}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    Box(
                                        Modifier.size(10.dp).clip(CircleShape)
                                            .background(statusColor(controller.status)),
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { onClose(controller) }) {
                                        // 活跃 → 断开（断链图标）；已断开 → 从列表移除
                                        if (controller.status == ConnStatus.CONNECTED ||
                                            controller.status == ConnStatus.CONNECTING ||
                                            controller.status == ConnStatus.AUTH
                                        ) {
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

internal fun statusColor(status: ConnStatus): Color = when (status) {
    ConnStatus.CONNECTED -> Color(0xFF4CAF50)
    ConnStatus.CONNECTING, ConnStatus.AUTH -> Color(0xFFFFA726)
    ConnStatus.CLOSED, ConnStatus.IDLE -> Color(0xFF9E9E9E)
    ConnStatus.ERROR -> Color(0xFFEF5350)
}
