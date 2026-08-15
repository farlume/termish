package dev.mssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 连接页：所有活跃/历史会话，点击重入终端，右侧按钮断开。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    sessions: List<TerminalController>,
    onOpen: (TerminalController) -> Unit,
    onClose: (TerminalController) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("连接") }) },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "暂无会话\n在「主机」页点击主机即可发起连接",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(sessions, key = { it.host.id }) { controller ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(controller) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(controller.status)))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(controller.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${controller.host.username}@${controller.host.hostname}:${controller.host.port} · ${statusText(controller.status)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { onClose(controller) }) {
                        Icon(Icons.Default.Close, contentDescription = "断开")
                    }
                }
                HorizontalDivider()
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

internal fun statusText(status: ConnStatus): String = when (status) {
    ConnStatus.IDLE -> "未连接"
    ConnStatus.CONNECTING -> "连接中"
    ConnStatus.AUTH -> "认证中"
    ConnStatus.CONNECTED -> "已连接"
    ConnStatus.CLOSED -> "已断开"
    ConnStatus.ERROR -> "连接失败"
}
