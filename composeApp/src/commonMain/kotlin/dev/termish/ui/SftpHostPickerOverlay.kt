package dev.termish.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.termish.data.ConnectionMode
import dev.termish.data.Host
import dev.termish.util.monospaceFontFamily

/**
 * New SFTP connection 覆盖层：盖在当前页面之上（非压栈导航）。
 * Header = X + 标题；下方 hosts 列表样式与首页一致，仅可点选。
 */
@Composable
fun SftpHostPickerOverlay(
    hosts: List<Host>,
    onDismiss: () -> Unit,
    onSelect: (Host) -> Unit,
) {
    val s = LocalAppStrings.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = s.navBack)
                }
                Text(
                    s.sftpNewTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 分组标题（与首页「我的主机」同款样式，左距对齐卡片 8dp）
            Text(
                s.sftpSectionTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(hosts, key = { it.id }) { host ->
                    SftpHostRow(host = host, onClick = { onSelect(host) })
                }
            }
        }
    }
}

/** 选主机列表行：样式与首页卡片一致（头像 + IP + 连接详情），仅可点选。 */
@Composable
private fun SftpHostRow(host: Host, onClick: () -> Unit) {
    val s = LocalAppStrings.current
    val sys = host.system.ifBlank { host.hostname }
    val address = if (host.port != 22) "${host.hostname}:${host.port}" else host.hostname
    val detail = buildString {
        append(if (host.connectionMode == ConnectionMode.MOSH) s.hostsModeMosh else s.hostsModeSsh)
        append(", ")
        append(host.username)
        if (host.system.isNotBlank()) {
            append(", ")
            append(host.system)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(systemColor(sys)),
                contentAlignment = Alignment.Center,
            ) {
                SystemAvatarIcon(sys, 22.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
