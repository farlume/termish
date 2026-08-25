package dev.termish.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.termish.agent.AgentBridgeController
import dev.termish.agent.AgentNativeSessionInfo
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.Spacing
import dev.termish.util.monospaceFontFamily

/** Agent 自有历史发现与导入 UI；从主工作区拆出，隔离扫描/搜索/导入状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeHistorySheet(
    controller: AgentBridgeController,
    onDismiss: () -> Unit,
    onOpen: (AgentNativeSessionInfo) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var query by rememberSaveable { mutableStateOf("") }
    val visibleSessions =
        remember(controller.nativeSessions, query) {
            controller.nativeSessions.filter { session ->
                query.isBlank() ||
                    session.title.contains(query, ignoreCase = true) ||
                    session.agent.contains(query, ignoreCase = true) ||
                    session.cwd.contains(query, ignoreCase = true)
            }
        }
    LaunchedEffect(controller) { controller.loadNativeHistory() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().height(Sizes.AgentDirectorySheet).navigationBarsPadding()) {
            Text(
                strings.nativeHistory,
                Modifier.padding(horizontal = Spacing.Lg),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                strings.nativeHistoryHint,
                Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                placeholder = { Text(strings.searchNativeHistory) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            if (controller.nativeHistoryLoading && controller.nativeSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (visibleSessions.isEmpty()) {
                        item {
                            Text(
                                strings.noNativeHistory,
                                Modifier.padding(Spacing.Lg),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(visibleSessions, key = { "${it.agent}:${it.nativeId}" }) { session ->
                        val importing = controller.nativeHistoryImportingId == session.nativeId
                        ListItem(
                            headlineContent = {
                                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        "${session.agent} · ${strings.nativeMessageCount(session.messageCount)}",
                                        maxLines = 1,
                                    )
                                    Text(
                                        session.cwd,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = monospaceFontFamily(),
                                    )
                                }
                            },
                            leadingContent = { AgentAvatar(session.agent, Modifier.size(Sizes.IconMedium)) },
                            trailingContent = {
                                if (importing) {
                                    CircularProgressIndicator(
                                        Modifier.size(Sizes.IconSmall),
                                        strokeWidth = Sizes.BorderThin,
                                    )
                                } else {
                                    TextButton(
                                        onClick = { onOpen(session) },
                                        enabled = controller.nativeHistoryImportingId == null,
                                    ) {
                                        Text(
                                            if (session.importedSessionId == null) {
                                                strings.importNativeHistory
                                            } else {
                                                strings.openImportedHistory
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (controller.nativeHistoryLoading && controller.nativeSessions.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
