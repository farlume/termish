package dev.termish.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.Spacing
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun AgentChatTimeline(
    turns: List<AgentTurnUi>,
    followOutput: Boolean,
    hasOlderMessages: Boolean,
    loadingOlderMessages: Boolean,
    onFollowOutputChange: (Boolean) -> Unit,
    onLoadOlderMessages: () -> Unit,
    modifier: Modifier = Modifier,
    turnContent: @Composable (AgentTurnUi, () -> Unit) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val userScrollConnection =
        remember(listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) onFollowOutputChange(false)
                    return Offset.Zero
                }
            }
        }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collectLatest { (isScrollInProgress, canScrollForward) ->
                if (shouldResumeAgentOutputFollow(isScrollInProgress, canScrollForward)) {
                    onFollowOutputChange(true)
                }
            }
    }
    LaunchedEffect(turns, followOutput) {
        if (turns.isNotEmpty() && followOutput) {
            listState.scrollToItem(turns.lastIndex + if (hasOlderMessages) 1 else 0, scrollOffset = 1_000_000)
        }
    }
    LaunchedEffect(listState, turns.size, followOutput, hasOlderMessages) {
        if (!followOutput) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            Triple(layout.totalItemsCount, last?.index, last?.size)
        }.collectLatest {
            if (turns.isNotEmpty()) {
                listState.scrollToItem(turns.lastIndex + if (hasOlderMessages) 1 else 0, scrollOffset = 1_000_000)
            }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScrollConnection),
            contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xl),
        ) {
            if (turns.isEmpty()) {
                item {
                    Text(
                        strings.welcomeTitle,
                        Modifier.fillMaxWidth().padding(vertical = Spacing.Xxl),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            if (hasOlderMessages) {
                item(key = "older-messages") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onLoadOlderMessages, enabled = !loadingOlderMessages) {
                            if (loadingOlderMessages) {
                                CircularProgressIndicator(
                                    Modifier.size(Sizes.IconSmall),
                                    strokeWidth = Sizes.BorderThin,
                                )
                                Spacer(Modifier.size(Spacing.Sm))
                            }
                            Text(if (loadingOlderMessages) strings.loadingEarlier else strings.loadEarlier)
                        }
                    }
                }
            }
            items(turns, key = { it.id }) { turn ->
                turnContent(turn) { onFollowOutputChange(false) }
            }
        }
        if (!atBottom && turns.isNotEmpty()) {
            FilledTonalIconButton(
                onClick = {
                    onFollowOutputChange(true)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.Md),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, strings.jumpToLatest)
            }
        }
    }
}
