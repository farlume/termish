package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.data.HostRepository
import dev.termish.data.Snippet
import dev.termish.data.TagGroup
import dev.termish.data.newId
import dev.termish.util.monospaceFontFamily
import kotlinx.datetime.Clock

/**
 * 命令片段管理页（设置页二级）：列表 + 搜索 + 标签过滤 + 新建/编辑 + 标签管理入口。
 * 片段为全局库（跨主机复用），标签从标签组多选（不自由输入）。
 */
@Composable
fun SnippetManageScreen(
    repository: HostRepository,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var snippets by remember { mutableStateOf(repository.listSnippets()) }
    var tags by remember { mutableStateOf(repository.listTagGroups()) }
    var query by remember { mutableStateOf("") }
    var filterTagId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showTagPage by remember { mutableStateOf(false) }

    val q = query.trim()
    val filtered = snippets
        .filter { filterTagId == null || it.tagIds.contains(filterTagId) }
        .filter {
            q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true)
        }
        .sortedByDescending { it.updatedAt }

    Scaffold(
        topBar = {
            TermishLargeHeader(
                title = s.snippetsTitle,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showTagPage = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = s.tagsTitle)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = null; showEditor = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Default.Add, s.snippetsAdd) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text(s.snippetsSearch) },
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
            // 标签过滤 chips（横向滚动；「全部」+ 每个标签组）
            if (tags.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filterTagId == null,
                        onClick = { filterTagId = null },
                        label = { Text(s.snippetsTagAll) },
                    )
                    tags.forEach { t ->
                        FilterChip(
                            selected = filterTagId == t.id,
                            onClick = { filterTagId = if (filterTagId == t.id) null else t.id },
                            label = { Text(t.name) },
                        )
                    }
                }
            }
            if (snippets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            s.snippetsEmpty,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            s.snippetsEmptyHint,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        s.hostsNoMatch,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { snippet ->
                        SnippetRow(
                            snippet = snippet,
                            tags = tags,
                            onClick = {
                                editing = snippet
                                showEditor = true
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            }
        }
    }

    // 编辑页（新建/编辑共用）：二级覆盖层
    AnimatedVisibility(
        visible = showEditor,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SnippetEditPage(
            repository = repository,
            existing = editing,
            onBack = {
                showEditor = false
                snippets = repository.listSnippets()
                tags = repository.listTagGroups()
            },
        )
    }
    // 标签管理页
    AnimatedVisibility(
        visible = showTagPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        TagManagePage(
            repository = repository,
            onBack = {
                showTagPage = false
                tags = repository.listTagGroups()
            },
        )
    }
}

/** 片段列表行：名称 + 内容预览 + 标签 chips。 */
@Composable
private fun SnippetRow(
    snippet: Snippet,
    tags: List<TagGroup>,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                snippet.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 内容预览（等宽字体，灰）
            Text(
                snippet.content.replace("\n", " ").take(24),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val named = snippet.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
        if (named.isNotEmpty()) {
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                named.take(3).forEach { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** 片段新建/编辑表单：名称 / 命令内容 / 标签多选（从标签组选择，不手输）。 */
@Composable
private fun SnippetEditPage(
    repository: HostRepository,
    existing: Snippet?,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var tagIds by remember { mutableStateOf(existing?.tagIds ?: emptyList()) }
    val tags = remember { repository.listTagGroups() }
    var pendingDelete by remember { mutableStateOf(false) }

    fun save() {
        val trimmedName = name.trim()
        val trimmedContent = content.trim()
        if (trimmedName.isEmpty() || trimmedContent.isEmpty()) return
        repository.upsertSnippet(
            Snippet(
                id = existing?.id ?: newId(),
                name = trimmedName,
                content = trimmedContent,
                tagIds = tagIds,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        onBack()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                if (existing == null) s.snippetsAdd else s.snippetEdit,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (existing != null) {
                IconButton(onClick = { pendingDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = s.snippetDelete, tint = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { save() }, enabled = name.isNotBlank() && content.isNotBlank()) {
                Text(s.editSave, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(s.snippetName) },
                singleLine = true,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(s.snippetContent) },
                minLines = 3,
                textStyle = LocalTextStyle.current.copy(fontFamily = monospaceFontFamily()),
            )
            // 标签：从标签组多选（chips），无自由输入
            Text(
                s.snippetTags,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            if (tags.isEmpty()) {
                Text(
                    s.tagsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { t ->
                        FilterChip(
                            selected = t.id in tagIds,
                            onClick = {
                                tagIds = if (t.id in tagIds) tagIds - t.id else tagIds + t.id
                            },
                            label = { Text(t.name) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(s.snippetDeleteConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    repository.deleteSnippet(existing!!.id)
                    onBack()
                }) { Text(s.snippetDelete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text(s.terminalCancel) }
            },
        )
    }
}

/** 标签组管理页：增删改（改名即全库片段跟随；删除级联清理引用）。 */
@Composable
fun TagManagePage(
    repository: HostRepository,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var tags by remember { mutableStateOf(repository.listTagGroups()) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TagGroup?>(null) }
    val snippetCount = { id: String -> repository.listSnippets().count { id in it.tagIds } }

    fun addTag() {
        val name = newName.trim()
        if (name.isEmpty()) return
        // 同名不重复建（大小写不敏感），避免标签组碎片化
        if (tags.none { it.name.equals(name, ignoreCase = true) }) {
            repository.upsertTagGroup(TagGroup(newId(), name))
        }
        newName = ""
        tags = repository.listTagGroups()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.tagsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                s.tagsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (tags.isEmpty()) {
                Text(
                    s.tagsEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            tags.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { pendingDelete = t }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = s.tagDelete,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            }
            // 新增标签行
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(s.tagName) },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { addTag() }, enabled = newName.isNotBlank()) {
                    Text(s.tagAdd)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { tag ->
        val affected = snippetCount(tag.id)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(s.tagDelete) },
            text = {
                Text(
                    if (affected > 0) s.tagDeleteConfirm(affected) else s.tagDeleteConfirm(0),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteTagGroup(tag.id)
                    pendingDelete = null
                    tags = repository.listTagGroups()
                }) { Text(s.tagDelete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(s.terminalCancel) }
            },
        )
    }
}

/**
 * 终端内片段插入面板（键盘工具栏「{}」触发）：搜索 + 标签过滤 + 列表。
 * 点按 = 插入当前输入行（不带回车，补参数后手动回车）；长按 = 直接执行/删除。
 * 字节经 sendText 直达远端，天然绕开 IME 组合态管线。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SnippetInsertSheet(
    repository: HostRepository,
    /** content = 片段内容，run = true 表示直接执行（带回车）。 */
    onUse: (content: String, run: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var snippets by remember { mutableStateOf(repository.listSnippets()) }
    var tags by remember { mutableStateOf(repository.listTagGroups()) }
    var query by remember { mutableStateOf("" ) }
    var filterTagId by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Snippet?>(null) }
    var pendingDelete by remember { mutableStateOf<Snippet?>(null) }

    val q = query.trim()
    val filtered = snippets
        .filter { filterTagId == null || it.tagIds.contains(filterTagId) }
        .filter {
            q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true)
        }
        .sortedByDescending { it.updatedAt }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                s.snippetInsertTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                s.snippetInsertHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(s.snippetsSearch) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            if (tags.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filterTagId == null,
                        onClick = { filterTagId = null },
                        label = { Text(s.snippetsTagAll) },
                    )
                    tags.forEach { t ->
                        FilterChip(
                            selected = filterTagId == t.id,
                            onClick = { filterTagId = if (filterTagId == t.id) null else t.id },
                            label = { Text(t.name) },
                        )
                    }
                }
            }
            if (snippets.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.snippetsEmpty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            } else if (filtered.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.hostsNoMatch,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(filtered, key = { it.id }) { snippet ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onUse(snippet.content, false) },
                                    onLongClick = { menuFor = snippet },
                                )
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    snippet.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    snippet.content.replace("\n", " ").take(20),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = monospaceFontFamily(),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val named = snippet.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
                            if (named.isNotEmpty()) {
                                Row(
                                    Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    named.take(3).forEach { name ->
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // 长按菜单：直接执行 / 删除（必须在 sheet 内渲染，否则被 dialog 层遮挡）
        menuFor?.let { snippet ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuFor = null },
            ) {
                DropdownMenuItem(
                    text = { Text(s.snippetRun) },
                    onClick = {
                        menuFor = null
                        onUse(snippet.content, true)
                    },
                )
                DropdownMenuItem(
                    text = { Text(s.snippetDelete) },
                    onClick = {
                        menuFor = null
                        pendingDelete = snippet
                    },
                )
            }
        }

        pendingDelete?.let { snippet ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(s.snippetDeleteConfirm) },
                confirmButton = {
                    TextButton(onClick = {
                        repository.deleteSnippet(snippet.id)
                        pendingDelete = null
                        snippets = repository.listSnippets()
                    }) { Text(s.snippetDelete, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text(s.terminalCancel) }
                },
            )
        }
    }
}
