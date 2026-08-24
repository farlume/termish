package dev.termish.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State as MarkdownRenderState
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.termish.agent.AgentAttachment
import dev.termish.agent.AgentBridgeAgent
import dev.termish.agent.AgentBridgeController
import dev.termish.agent.AgentBridgeSessionInfo
import dev.termish.agent.AgentBridgeState
import dev.termish.agent.AgentChatMessage
import dev.termish.agent.AgentDirectory
import dev.termish.agent.AgentInstallPhase
import dev.termish.agent.AgentPendingAttachment
import dev.termish.agent.AgentProviderRuntime
import dev.termish.data.AgentProvider
import dev.termish.data.AgentProviderType
import dev.termish.data.AgentWorkspacePreferences
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.agentProviderKeyAccount
import dev.termish.data.newId
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.agent_claude
import dev.termish.generated.resources.agent_codex
import dev.termish.generated.resources.agent_gemini
import dev.termish.generated.resources.agent_opencode
import dev.termish.generated.resources.agent_pi
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ui.theme.AgentBrandColors
import dev.termish.ui.theme.Corners
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.Spacing
import dev.termish.ui.theme.StatusColors
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.monospaceFontFamily
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

private const val TYPING_DOT_DURATION_MILLIS = 480
private const val TYPING_DOT_STAGGER_MILLIS = 120
private const val TYPING_DOT_IDLE_ALPHA = 0.28f
private const val TYPEWRITER_FRAME_INTERVAL_MILLIS = 32L
private const val TOOL_PREVIEW_MAX_LINES = 5

private enum class AgentWorkspacePage { CHAT, AGENTS, SETTINGS }

@OptIn(ExperimentalResourceApi::class)
@Composable
fun AgentScreen(
    host: Host,
    repository: HostRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var authRequest by remember { mutableStateOf<AuthPromptRequest?>(null) }
    var hostKeyRequest by remember { mutableStateOf<HostKeyRequest?>(null) }
    val callbacks =
        remember(host.id) {
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                    val request = AuthPromptRequest(prompt)
                    authRequest = request
                    return request.deferred.await()
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    val known = repository.getHost(host.id)?.knownHostFingerprint ?: host.knownHostFingerprint
                    if (known == hostKey.fingerprintSha256) return true
                    if (known == null && !repository.loadSettings().verifyHostKeyOnFirstUse) return true
                    val request = HostKeyRequest(hostKey, known != null, known)
                    hostKeyRequest = request
                    val accepted = runBlocking { request.deferred.await() }
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
            }
        }
    val controller = remember(host.id) { AgentBridgeController(host, repository, callbacks, scope) }

    LaunchedEffect(controller) { controller.connect() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    authRequest?.let { request ->
        AuthPromptDialog(request.prompt) { answers ->
            authRequest = null
            request.deferred.complete(answers)
        }
    }
    hostKeyRequest?.let { request ->
        HostKeyDialog(request.key, request.changed, request.previousFingerprint) { accepted ->
            hostKeyRequest = null
            request.deferred.complete(accepted)
        }
    }

    AgentWorkspace(
        controller = controller,
        repository = repository,
        initialPreferences = repository.loadAgentPreferences(host.id),
        onSavePreferences = { repository.saveAgentPreferences(host.id, it) },
        onInstallBridge = { scope.launch { controller.installBridge(Res.readBytes("files/termish-agent.pyz")) } },
        onExit = onBack,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentWorkspace(
    controller: AgentBridgeController,
    repository: HostRepository,
    initialPreferences: AgentWorkspacePreferences,
    onSavePreferences: (AgentWorkspacePreferences) -> Unit,
    onInstallBridge: () -> Unit,
    onExit: () -> Unit,
) {
    AgentImeResizeEffect()
    val strings = LocalAppStrings.current.nativeAgents
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    var page by remember { mutableStateOf(AgentWorkspacePage.CHAT) }
    var preferences by remember { mutableStateOf(initialPreferences) }
    var selectedAgent by remember { mutableStateOf(initialPreferences.defaultAgent) }
    var directoryPickerOpen by remember { mutableStateOf(false) }
    var sessionActions by remember { mutableStateOf<AgentBridgeSessionInfo?>(null) }
    var providers by remember { mutableStateOf(repository.loadSettings().agentProviders) }
    var editingProvider by remember { mutableStateOf<AgentProvider?>(null) }
    var addingProvider by remember { mutableStateOf(false) }
    val canReturnToAgentHome = page == AgentWorkspacePage.CHAT && controller.currentSession != null
    val density = LocalDensity.current
    val backGestureEdge = with(density) { Sizes.AgentBackGestureEdge.toPx() }
    val backGestureThreshold = with(density) { Sizes.AgentBackGestureThreshold.toPx() }

    LaunchedEffect(controller.agents, preferences.defaultAgent) {
        val preferred = controller.agents.firstOrNull { it.id == preferences.defaultAgent && it.available && it.supported }
        val fallback = controller.agents.firstOrNull { it.available && it.supported }
        selectedAgent = preferred?.id ?: selectedAgent.takeIf { id -> controller.agents.any { it.id == id && it.available } } ?: fallback?.id.orEmpty()
    }

    fun savePreferences(value: AgentWorkspacePreferences) {
        preferences = value
        onSavePreferences(value)
    }

    fun saveProviders(value: List<AgentProvider>) {
        providers = value
        repository.saveSettings(repository.loadSettings().copy(agentProviders = value))
    }

    fun providerRuntime(
        agentId: String,
        providerId: String?,
    ): AgentProviderRuntime? {
        val provider = providers.firstOrNull { it.id == providerId && it.enabled } ?: return null
        if (!providerSupportsAgent(provider.type, agentId)) return null
        return AgentProviderRuntime(
            id = provider.id,
            type = provider.type.name.lowercase(),
            apiKey = SecretStore.get(SECRET_SERVICE, agentProviderKeyAccount(provider.id)).orEmpty(),
            baseUrl = provider.baseUrl,
        )
    }

    PlatformBackHandler(enabled = canReturnToAgentHome) { controller.newChat() }

    val backSwipeModifier =
        if (canReturnToAgentHome) {
            Modifier.pointerInput(controller.currentSession?.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x > backGestureEdge) return@awaitEachGesture
                    val start = down.position
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val horizontal = change.position.x - start.x
                        val vertical = change.position.y - start.y
                        if (horizontal >= backGestureThreshold && horizontal > abs(vertical)) {
                            change.consume()
                            controller.newChat()
                            break
                        }
                    }
                }
            }
        } else {
            Modifier
        }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AgentDrawer(
                controller = controller,
                page = page,
                onNewChat = {
                    controller.newChat()
                    page = AgentWorkspacePage.CHAT
                    scope.launch { drawerState.close() }
                },
                onSelectSession = { session ->
                    controller.selectSession(session.id)
                    page = AgentWorkspacePage.CHAT
                    scope.launch { drawerState.close() }
                },
                onSessionActions = { sessionActions = it },
                onPage = {
                    page = it
                    scope.launch { drawerState.close() }
                },
                onExit = onExit,
            )
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().then(backSwipeModifier),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .statusBarsPadding()
                        .padding(top = Sizes.AgentChromeClearance),
                ) {
                    when (controller.state) {
                        AgentBridgeState.IDLE, AgentBridgeState.CONNECTING -> AgentLoading(strings.connecting)
                        AgentBridgeState.NEEDS_INSTALL ->
                            AgentBridgeInstall(
                                if (controller.bridgeVersion == null) strings.serviceMissing else strings.serviceOutdated,
                                strings.serviceHint,
                                if (controller.bridgeVersion == null) strings.installService else strings.updateService,
                                controller.installLog,
                                onInstallBridge,
                            )
                        AgentBridgeState.INSTALLING -> AgentLoading(strings.installingService, controller.installLog)
                        AgentBridgeState.NO_PYTHON ->
                            AgentBridgeInstall(strings.serviceMissing, strings.noPython, strings.retry, controller.installLog, controller::connect)
                        AgentBridgeState.ERROR ->
                            AgentBridgeInstall(
                                controller.errorMessage ?: strings.serviceMissing,
                                strings.serviceHint,
                                strings.retry,
                                controller.installLog,
                                controller::connect,
                            )
                        AgentBridgeState.READY ->
                            when (page) {
                                AgentWorkspacePage.AGENTS -> AgentManagement(controller)
                                AgentWorkspacePage.SETTINGS ->
                                    AgentSettings(
                                        controller,
                                        preferences,
                                        ::savePreferences,
                                        providers,
                                        { addingProvider = true },
                                        { editingProvider = it },
                                        { directoryPickerOpen = true },
                                        onInstallBridge,
                                    )
                                AgentWorkspacePage.CHAT ->
                                    if (controller.currentSession == null) {
                                        AgentHome(
                                            controller,
                                            selectedAgent,
                                            {
                                                selectedAgent = it
                                                savePreferences(preferences.copy(defaultAgent = it))
                                            },
                                            preferences,
                                            providers,
                                            providerRuntime(
                                                selectedAgent,
                                                preferences.providerByAgent[selectedAgent],
                                            ),
                                            repository,
                                            { directoryPickerOpen = true },
                                        )
                                    } else {
                                        val session = controller.currentSession
                                        AgentChat(
                                            controller,
                                            preferences,
                                            providers.firstOrNull { it.id == session?.provider },
                                            providerRuntime(session?.agent.orEmpty(), session?.provider),
                                            repository,
                                        )
                                    }
                            }
                    }
                }
                AgentFloatingChrome(
                    controller = controller,
                    page = page,
                    onMenu = { scope.launch { drawerState.open() } },
                    onSessionActions = { controller.currentSession?.let { sessionActions = it } },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    if (directoryPickerOpen) {
        DirectoryPickerSheet(
            controller,
            preferences.defaultDirectory,
            { directoryPickerOpen = false },
            {
                savePreferences(preferences.copy(defaultDirectory = it))
                directoryPickerOpen = false
            },
        )
    }
    sessionActions?.let { session ->
        SessionActionsSheet(
            session,
            { sessionActions = null },
            {
                controller.renameSession(session.id, it)
                sessionActions = null
            },
            {
                controller.deleteSession(session.id)
                sessionActions = null
            },
        )
    }
    if (addingProvider || editingProvider != null) {
        AgentProviderDialog(
            initial = editingProvider,
            onDismiss = {
                addingProvider = false
                editingProvider = null
            },
            onSave = { provider, apiKey ->
                SecretStore.set(SECRET_SERVICE, agentProviderKeyAccount(provider.id), apiKey)
                saveProviders(providers.filterNot { it.id == provider.id } + provider)
                addingProvider = false
                editingProvider = null
            },
            onDelete = { provider ->
                SecretStore.delete(SECRET_SERVICE, agentProviderKeyAccount(provider.id))
                saveProviders(providers.filterNot { it.id == provider.id })
                savePreferences(
                    preferences.copy(
                        providerByAgent = preferences.providerByAgent.filterValues { it != provider.id },
                    ),
                )
                addingProvider = false
                editingProvider = null
            },
        )
    }
}

@Composable
private fun AgentDrawer(
    controller: AgentBridgeController,
    page: AgentWorkspacePage,
    onNewChat: () -> Unit,
    onSelectSession: (AgentBridgeSessionInfo) -> Unit,
    onSessionActions: (AgentBridgeSessionInfo) -> Unit,
    onPage: (AgentWorkspacePage) -> Unit,
    onExit: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    ModalDrawerSheet(Modifier.fillMaxWidth(Sizes.AgentDrawerWidthFraction)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(Spacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(Corners.Md)) {
                Icon(
                    Icons.Default.SmartToy,
                    null,
                    Modifier.padding(Spacing.Md).size(Sizes.IconMedium),
                    MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(strings.workspace, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(controller.host.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onNewChat, Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg)) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.size(Spacing.Sm))
            Text(strings.newChat)
        }
        NavigationDrawerItem(
            label = { Text(strings.backToTermish) },
            selected = false,
            icon = { Icon(Icons.Default.Terminal, null) },
            onClick = onExit,
            modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        )
        HorizontalDivider(Modifier.padding(horizontal = Spacing.Lg), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(Icons.Default.History, null, Modifier.size(Sizes.IconSmall))
            Text(strings.recentChats, style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.Md)) {
            if (controller.sessions.isEmpty()) {
                item {
                    Text(
                        strings.noSessions,
                        Modifier.padding(Spacing.Lg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(controller.sessions, key = { it.id }) { session ->
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(session.title, maxLines = 1)
                            Text(session.agent, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    selected = page == AgentWorkspacePage.CHAT && controller.currentSession?.id == session.id,
                    icon = {
                        Box {
                            AgentAvatar(session.agent, Modifier.size(Sizes.IconMedium))
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(Sizes.StatusDot)
                                    .background(
                                        if (session.busy) StatusColors.Warning else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(Corners.Full),
                                    ),
                            )
                        }
                    },
                    badge = {
                        IconButton({ onSessionActions(session) }) {
                            Icon(Icons.Default.MoreVert, strings.sessionActions)
                        }
                    },
                    onClick = { onSelectSession(session) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationDrawerItem(
            label = { Text(strings.manageAgents) },
            selected = page == AgentWorkspacePage.AGENTS,
            icon = { Icon(Icons.Default.SmartToy, null) },
            onClick = { onPage(AgentWorkspacePage.AGENTS) },
            modifier = Modifier.padding(horizontal = Spacing.Md),
        )
        NavigationDrawerItem(
            label = { Text(strings.settings) },
            selected = page == AgentWorkspacePage.SETTINGS,
            icon = { Icon(Icons.Default.Settings, null) },
            onClick = { onPage(AgentWorkspacePage.SETTINGS) },
            modifier = Modifier.padding(horizontal = Spacing.Md).navigationBarsPadding(),
        )
    }
}

@Composable
private fun AgentFloatingChrome(
    controller: AgentBridgeController,
    page: AgentWorkspacePage,
    onMenu: () -> Unit,
    onSessionActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current.nativeAgents
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onMenu) { Icon(Icons.Default.Menu, strings.workspace) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (controller.reconnecting) {
                CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin)
                Spacer(Modifier.size(Spacing.Sm))
            }
            if (page == AgentWorkspacePage.CHAT && controller.currentSession != null) {
                IconButton(onSessionActions) {
                    Icon(Icons.Default.MoreVert, strings.sessionActions)
                }
            }
        }
    }
}

@Composable
private fun AgentLoading(
    label: String,
    detail: String = "",
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            CircularProgressIndicator(Modifier.size(Sizes.IconMedium))
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail.isNotBlank()) {
                Text(
                    detail.takeLast(2_000),
                    Modifier.padding(horizontal = Spacing.Xl),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentBridgeInstall(
    title: String,
    hint: String,
    action: String,
    log: String,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(Spacing.Lg), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(Corners.Lg),
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.Xl), verticalArrangement = Arrangement.spacedBy(Spacing.Lg)) {
                Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (log.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Corners.Sm)) {
                        Text(log.takeLast(4_000), Modifier.padding(Spacing.Md), style = MaterialTheme.typography.bodySmall, fontFamily = monospaceFontFamily())
                    }
                }
                Button(onAction, Modifier.fillMaxWidth()) { Text(action) }
            }
        }
    }
}

@Composable
private fun AgentHome(
    controller: AgentBridgeController,
    selectedAgent: String,
    onSelectAgent: (String) -> Unit,
    preferences: AgentWorkspacePreferences,
    providers: List<AgentProvider>,
    provider: AgentProviderRuntime?,
    repository: HostRepository,
    onChooseDirectory: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var snippetOpen by remember { mutableStateOf(false) }
    val pickFiles = rememberFilePicker { attachments = attachments + it }
    val available = controller.agents.filter { it.available && it.supported }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    Text(strings.welcomeTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(strings.welcomeHint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Text(strings.chooseAgent, style = MaterialTheme.typography.labelLarge) }
            controller.errorMessage?.takeIf { it.isNotBlank() && !controller.reconnecting }?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) { AgentInlineError(message) }
            }
            items(available, key = { it.id }) { agent ->
                CompactAgentCard(agent, selectedAgent == agent.id) { onSelectAgent(agent.id) }
            }
            if (available.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(strings.noAvailableAgent, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AgentComposer(
            input,
            { input = it },
            attachments,
            pickFiles,
            { attachments = attachments - it },
            preferences.defaultDirectory,
            onChooseDirectory,
            controller.agents.firstOrNull { it.id == selectedAgent }?.label,
            providers.firstOrNull { it.id == preferences.providerByAgent[selectedAgent] }?.name,
            controller.busy,
            controller.attachmentProgress,
            selectedAgent.isNotBlank(),
            { snippetOpen = true },
            {
                controller.startConversation(
                    selectedAgent,
                    preferences.defaultDirectory.ifBlank { null },
                    preferences.defaultModel.ifBlank { null },
                    provider,
                    input.trim().ifBlank { strings.attachmentPrompt },
                    attachments.map { it.toAgentAttachment() },
                ) {
                    input = ""
                    attachments = emptyList()
                }
            },
            controller::abort,
        )
    }
    if (snippetOpen) {
        AgentSnippetSheet(
            repository = repository,
            onUse = { content -> input = appendSnippet(input, content) },
            onDismiss = { snippetOpen = false },
        )
    }
}

@Composable
private fun AgentInlineError(message: String) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        shape = RoundedCornerShape(Corners.Md),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompactAgentCard(
    agent: AgentBridgeAgent,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Corners.Md),
        border = BorderStroke(Sizes.BorderThin, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            AgentAvatar(agent.id, Modifier.size(Sizes.IconMedium))
            Text(agent.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AgentAvatar(
    agentId: String,
    modifier: Modifier = Modifier.size(Sizes.AgentAvatar),
) {
    val resource = agentBrandResource(agentId)
    val tint =
        when (agentId.lowercase()) {
            "claude" -> AgentBrandColors.Claude
            "gemini" -> AgentBrandColors.Gemini
            else -> MaterialTheme.colorScheme.onSurface
        }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Corners.Md),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (resource == null) {
                Icon(Icons.Default.SmartToy, null, Modifier.padding(Spacing.Xs), tint = tint)
            } else {
                Icon(painterResource(resource), null, Modifier.padding(Spacing.Xs), tint = tint)
            }
        }
    }
}

private fun agentBrandResource(agentId: String): DrawableResource? =
    when (agentId.lowercase()) {
        "codex" -> Res.drawable.agent_codex
        "claude" -> Res.drawable.agent_claude
        "gemini" -> Res.drawable.agent_gemini
        "opencode" -> Res.drawable.agent_opencode
        "pi" -> Res.drawable.agent_pi
        else -> null
    }

@Composable
private fun AgentManagement(controller: AgentBridgeController) {
    val strings = LocalAppStrings.current.nativeAgents
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.Lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(strings.manageAgents, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item(span = { GridItemSpan(maxLineSpan) }) { InstallationStatus(controller) }
        items(controller.agents, key = { it.id }) { agent ->
            AgentManagementCard(agent, controller.installStatus.agentId == agent.id, controller::installAgent)
        }
    }
}

@Composable
private fun AgentManagementCard(
    agent: AgentBridgeAgent,
    installing: Boolean,
    onInstall: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    Card(border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Lg), verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                AgentAvatar(agent.id, Modifier.size(Sizes.IconMedium))
                Text(agent.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(
                    Icons.Default.Circle,
                    null,
                    Modifier.size(Sizes.StatusDot),
                    if (agent.available) StatusColors.Connected else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                when {
                    !agent.supported -> strings.agentUnsupported
                    agent.available -> strings.agentInstalled
                    else -> strings.agentMissing
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!agent.available && agent.supported) {
                FilledTonalButton({ onInstall(agent.id) }, Modifier.fillMaxWidth(), enabled = !installing) {
                    if (installing) CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin) else Text(strings.installAgent)
                }
            }
        }
    }
}

@Composable
private fun InstallationStatus(controller: AgentBridgeController) {
    val strings = LocalAppStrings.current.nativeAgents
    val status = controller.installStatus
    if (status.phase == AgentInstallPhase.IDLE) return
    val color =
        when (status.phase) {
            AgentInstallPhase.SUCCEEDED -> StatusColors.Connected
            AgentInstallPhase.FAILED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Corners.Md)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Lg), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                when (status.phase) {
                    AgentInstallPhase.INSTALLING -> CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin)
                    AgentInstallPhase.SUCCEEDED -> Icon(Icons.Default.CheckCircle, null, tint = color)
                    AgentInstallPhase.FAILED -> Icon(Icons.Default.Error, null, tint = color)
                    AgentInstallPhase.IDLE -> Unit
                }
                Text(
                    when (status.phase) {
                        AgentInstallPhase.INSTALLING -> strings.installRunning
                        AgentInstallPhase.SUCCEEDED -> strings.installSucceeded
                        AgentInstallPhase.FAILED -> strings.installFailed
                        AgentInstallPhase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                )
            }
            if (status.detail.isNotBlank()) {
                Text(status.detail.takeLast(2_000), style = MaterialTheme.typography.bodySmall, fontFamily = monospaceFontFamily())
            }
        }
    }
}

@Composable
private fun AgentSettings(
    controller: AgentBridgeController,
    preferences: AgentWorkspacePreferences,
    onChange: (AgentWorkspacePreferences) -> Unit,
    providers: List<AgentProvider>,
    onAddProvider: () -> Unit,
    onEditProvider: (AgentProvider) -> Unit,
    onChooseDirectory: () -> Unit,
    onUpdateBridge: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.Lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        item {
            Text(strings.settings, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item { SettingsSectionTitle(strings.defaultAgent) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                controller.agents
                    .filter { it.available && it.supported }
                    .chunked(2)
                    .forEach { rowAgents ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                            rowAgents.forEach { agent ->
                                CompactAgentCard(
                                    agent = agent,
                                    selected = preferences.defaultAgent == agent.id,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    onChange(preferences.copy(defaultAgent = agent.id))
                                }
                            }
                            if (rowAgents.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
            }
        }
        item {
            OutlinedTextField(
                preferences.defaultModel,
                { onChange(preferences.copy(defaultModel = it)) },
                Modifier.fillMaxWidth(),
                label = { Text(strings.defaultModel) },
                supportingText = { Text(strings.defaultModelHint) },
                singleLine = true,
            )
        }
        item { SettingsActionRow(strings.defaultDirectory, preferences.defaultDirectory.ifBlank { strings.cwdHint }, Icons.Default.Folder, onChooseDirectory) }
        item { SettingsSectionTitle(strings.providers) }
        item {
            OutlinedButton(onAddProvider, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(Spacing.Sm))
                Text(strings.addProvider)
            }
        }
        items(providers, key = { it.id }) { provider ->
            val missingKey = SecretStore.get(SECRET_SERVICE, agentProviderKeyAccount(provider.id)).isNullOrBlank()
            SettingsActionRow(
                provider.name,
                if (missingKey) strings.providerMissingKey else provider.baseUrl,
                Icons.Default.SmartToy,
            ) { onEditProvider(provider) }
        }
        item { SettingsSectionTitle(strings.providerPerAgent) }
        items(controller.agents.filter { it.supported }, key = { "provider-${it.id}" }) { agent ->
            AgentProviderChoiceRow(
                agent = agent,
                providers = providers,
                selectedProviderId = preferences.providerByAgent[agent.id].orEmpty(),
            ) { providerId ->
                onChange(
                    preferences.copy(
                        providerByAgent =
                            preferences.providerByAgent.toMutableMap().apply {
                                if (providerId.isBlank()) remove(agent.id) else put(agent.id, providerId)
                            },
                    ),
                )
            }
        }
        item { SettingsSectionTitle(strings.appearance) }
        item {
            SettingsSwitchRow(strings.showThinking, strings.showThinkingHint, preferences.showThinking) {
                onChange(preferences.copy(showThinking = it))
            }
        }
        item {
            SettingsSwitchRow(strings.expandTools, strings.expandToolsHint, preferences.expandTools) {
                onChange(preferences.copy(expandTools = it))
            }
        }
        item { SettingsSectionTitle(strings.serviceReady) }
        item {
            Card(border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(Spacing.Lg), verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusColors.Connected)
                        Column(Modifier.weight(1f)) {
                            Text(strings.serviceReady, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${strings.bridgeVersion} ${controller.bridgeVersion.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(onUpdateBridge, Modifier.fillMaxWidth()) { Text(strings.updateService) }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, Modifier.padding(top = Spacing.Md), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun AgentProviderChoiceRow(
    agent: AgentBridgeAgent,
    providers: List<AgentProvider>,
    selectedProviderId: String,
    onSelect: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var expanded by remember { mutableStateOf(false) }
    val compatible = providers.filter { it.enabled && providerSupportsAgent(it.type, agent.id) }
    val selected = compatible.firstOrNull { it.id == selectedProviderId }
    Box {
        Surface(
            Modifier.fillMaxWidth().clickable { expanded = true },
            shape = RoundedCornerShape(Corners.Md),
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(Spacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                AgentAvatar(agent.id, Modifier.size(Sizes.IconMedium))
                Column(Modifier.weight(1f)) {
                    Text(agent.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        selected?.name ?: strings.builtInProvider,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected != null && SecretStore.get(SECRET_SERVICE, agentProviderKeyAccount(selected.id)).isNullOrBlank()) {
                        Text(
                            strings.providerMissingKey,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (providers.isNotEmpty() && compatible.isEmpty()) {
                        Text(
                            strings.providerIncompatible,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.Default.ExpandMore, null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(strings.builtInProvider)
                        Text(strings.builtInProviderHint, style = MaterialTheme.typography.labelSmall)
                    }
                },
                onClick = {
                    expanded = false
                    onSelect("")
                },
            )
            compatible.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.name) },
                    onClick = {
                        expanded = false
                        onSelect(provider.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentProviderDialog(
    initial: AgentProvider?,
    onDismiss: () -> Unit,
    onSave: (AgentProvider, String) -> Unit,
    onDelete: (AgentProvider) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val providerId = remember(initial?.id) { initial?.id ?: newId() }
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: strings.deepSeekProvider) }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl ?: "https://api.deepseek.com") }
    var apiKey by
        remember(initial?.id) {
            mutableStateOf(SecretStore.get(SECRET_SERVICE, agentProviderKeyAccount(providerId)).orEmpty())
        }
    var confirmDelete by remember(initial?.id) { mutableStateOf(false) }
    if (!confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (initial == null) strings.addProvider else initial.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    Text(
                        strings.deepSeekProvider,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerName) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        baseUrl,
                        { baseUrl = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerBaseUrl) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        apiKey,
                        { apiKey = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerApiKey) },
                        supportingText = { Text(strings.providerApiKeyHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (initial != null) {
                        TextButton({ confirmDelete = true }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(Spacing.Sm))
                            Text(strings.deleteProvider, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && baseUrl.startsWith("https://") && apiKey.isNotBlank(),
                    onClick = {
                        onSave(
                            AgentProvider(
                                id = providerId,
                                type = AgentProviderType.DEEPSEEK,
                                name = name.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                            ),
                            apiKey.trim(),
                        )
                    },
                ) { Text(strings.save) }
            },
            dismissButton = { TextButton(onDismiss) { Text(LocalAppStrings.current.terminalCancel) } },
        )
    }
    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(strings.deleteProvider) },
            text = { Text(strings.deleteProviderConfirm) },
            confirmButton = {
                TextButton({ onDelete(initial) }) {
                    Text(strings.deleteProvider, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton({ confirmDelete = false }) { Text(LocalAppStrings.current.terminalCancel) }
            },
        )
    }
}

private fun providerSupportsAgent(
    type: AgentProviderType,
    agentId: String,
): Boolean =
    when (type) {
        AgentProviderType.DEEPSEEK -> agentId in setOf("claude", "opencode", "pi")
    }

@Composable
private fun SettingsActionRow(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(Corners.Md),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Icon(icon, null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Surface(shape = RoundedCornerShape(Corners.Md), border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant)) {
        Row(
            Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(Spacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onChecked)
        }
    }
}

internal data class AgentTurnUi(
    val id: String,
    val user: AgentChatMessage?,
    val events: List<AgentChatMessage>,
    val running: Boolean,
)

private data class AgentTurnBuilder(
    val id: String,
    var user: AgentChatMessage? = null,
    val events: MutableList<AgentChatMessage> = mutableListOf(),
)

internal sealed interface AgentTurnBlock

internal data class AgentActivityBlock(
    val activities: List<AgentChatMessage>,
) : AgentTurnBlock

internal data class AgentMessageBlock(
    val message: AgentChatMessage,
) : AgentTurnBlock

internal fun buildAgentTurns(
    messages: List<AgentChatMessage>,
    busy: Boolean,
): List<AgentTurnUi> {
    val builders = mutableListOf<AgentTurnBuilder>()
    var current: AgentTurnBuilder? = null
    messages.forEachIndexed { index, message ->
        val messageTurnId =
            when {
                message.turnId.isNotBlank() -> message.turnId
                message.role == "user" -> "legacy-$index"
                else -> current?.id ?: "legacy-$index"
            }
        if (message.role == "user") {
            val existing = builders.firstOrNull { it.id == messageTurnId }
            if (existing != null) {
                existing.user = message
                current = existing
            } else {
                current = AgentTurnBuilder(messageTurnId, user = message)
                builders += current
            }
        } else {
            if (current == null || message.turnId.isNotBlank() && current?.id != message.turnId) {
                current = builders.firstOrNull { it.id == messageTurnId } ?: AgentTurnBuilder(messageTurnId).also(builders::add)
            }
            current?.events?.add(message)
        }
    }
    return builders.mapIndexed { index, builder ->
        AgentTurnUi(
            id = builder.id,
            user = builder.user,
            events = builder.events,
            running = builder.events.any { it.running } || busy && index == builders.lastIndex,
        )
    }
}

internal fun buildAgentTurnBlocks(
    events: List<AgentChatMessage>,
    showThinking: Boolean,
): List<AgentTurnBlock> {
    val blocks = mutableListOf<AgentTurnBlock>()
    val activities = mutableListOf<AgentChatMessage>()

    fun flushActivities() {
        if (activities.isNotEmpty()) {
            blocks += AgentActivityBlock(activities.toList())
            activities.clear()
        }
    }

    events.forEach { message ->
        when (message.role) {
            "thinking" -> if (showThinking) activities += message
            "tool" -> activities += message
            else -> {
                flushActivities()
                blocks += AgentMessageBlock(message)
            }
        }
    }
    flushActivities()
    return blocks
}

@Composable
private fun AgentChat(
    controller: AgentBridgeController,
    preferences: AgentWorkspacePreferences,
    providerConfig: AgentProvider?,
    provider: AgentProviderRuntime?,
    repository: HostRepository,
) {
    var input by remember(controller.currentSession?.id) { mutableStateOf("") }
    var attachments by remember(controller.currentSession?.id) { mutableStateOf<List<PickedFile>>(emptyList()) }
    var snippetOpen by remember(controller.currentSession?.id) { mutableStateOf(false) }
    val pickFiles = rememberFilePicker { attachments = attachments + it }
    val strings = LocalAppStrings.current.nativeAgents
    val scope = rememberCoroutineScope()
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    val turns = remember(controller.messages, controller.busy) { buildAgentTurns(controller.messages, controller.busy) }
    var followOutput by remember(controller.currentSession?.id) { mutableStateOf(true) }
    val atBottom by
        remember {
            androidx.compose.runtime.derivedStateOf {
                !listState.canScrollForward
            }
        }
    val userScrollConnection =
        remember(listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) followOutput = false
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput && !listState.canScrollForward) followOutput = true
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(turns.size, followOutput) {
        if (turns.isNotEmpty() && followOutput) {
            listState.scrollToItem(turns.lastIndex, scrollOffset = 1_000_000)
        }
    }
    LaunchedEffect(listState, turns.size, followOutput) {
        if (!followOutput) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            Triple(layout.totalItemsCount, last?.index, last?.size)
        }.collectLatest {
            if (turns.isNotEmpty()) listState.scrollToItem(turns.lastIndex, scrollOffset = 1_000_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                items(turns, key = { it.id }) { turn ->
                    val sessionAgent = controller.currentSession?.agent.orEmpty()
                    AgentTurn(
                        turn,
                        preferences.showThinking,
                        preferences.expandTools,
                        sessionAgent,
                        controller.agents.firstOrNull { it.id == sessionAgent }?.label ?: strings.assistantLabel,
                    )
                }
            }
            if (!atBottom && turns.isNotEmpty()) {
                FilledTonalIconButton(
                    onClick = {
                        followOutput = true
                        scope.launch {
                            listState.scrollToItem(turns.lastIndex, scrollOffset = 1_000_000)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.Md),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, strings.jumpToLatest)
                }
            }
        }
        AgentComposer(
            input,
            { input = it },
            attachments,
            pickFiles,
            { attachments = attachments - it },
            controller.currentSession?.cwd.orEmpty(),
            null,
            controller.currentSession?.agent,
            providerConfig?.name,
            controller.busy,
            controller.attachmentProgress,
            true,
            { snippetOpen = true },
            {
                followOutput = true
                controller.sendPrompt(
                    input.trim().ifBlank { strings.attachmentPrompt },
                    attachments.map { it.toAgentAttachment() },
                    provider,
                ) {
                    input = ""
                    attachments = emptyList()
                }
            },
            controller::abort,
        )
    }
    if (snippetOpen) {
        AgentSnippetSheet(
            repository = repository,
            onUse = { content -> input = appendSnippet(input, content) },
            onDismiss = { snippetOpen = false },
        )
    }
}

@Composable
private fun AgentComposer(
    input: String,
    onInput: (String) -> Unit,
    attachments: List<PickedFile>,
    onAttach: () -> Unit,
    onRemoveAttachment: (PickedFile) -> Unit,
    directory: String,
    onChooseDirectory: (() -> Unit)?,
    agentLabel: String?,
    providerLabel: String?,
    busy: Boolean,
    progress: String?,
    enabled: Boolean,
    onSnippets: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Spacing.Xs,
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(Corners.Lg),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Md).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            if (attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    attachments.forEach { file -> AttachmentChip(file.name, file.size) { onRemoveAttachment(file) } }
                }
            }
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin)
                    Text("${strings.uploadingAttachment} · $progress", style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = Sizes.TouchTarget).padding(horizontal = Spacing.Sm, vertical = Spacing.Md)) {
                if (input.isEmpty()) Text(strings.messageHint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    input,
                    onInput,
                    Modifier.fillMaxWidth(),
                    enabled = enabled && !busy,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    maxLines = 6,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                IconButton(onAttach, enabled = enabled && !busy) { Icon(Icons.Default.AttachFile, strings.attachFiles) }
                IconButton(onSnippets, enabled = enabled && !busy) {
                    Icon(Icons.Default.Code, LocalAppStrings.current.settingsSnippets)
                }
                Surface(
                    Modifier.weight(1f).clickable(enabled = onChooseDirectory != null) { onChooseDirectory?.invoke() },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(Corners.Full),
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(Sizes.IconSmall))
                        Text(directory.ifBlank { strings.selectDirectory }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
                agentLabel?.let {
                    Text(
                        listOfNotNull(it, providerLabel).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy) {
                    FilledTonalIconButton(onStop) { Icon(Icons.Default.Stop, strings.stop) }
                } else {
                    FilledIconButton(onSend, enabled = enabled && (input.isNotBlank() || attachments.isNotEmpty())) {
                        Icon(Icons.AutoMirrored.Filled.Send, strings.send)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    name: String,
    size: Long,
    onRemove: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    Surface(
        modifier = Modifier.widthIn(max = Sizes.AgentAttachmentChipMaxWidth),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(Corners.Md),
    ) {
        Row(
            Modifier.padding(start = Spacing.Md, end = Spacing.Xs, top = Spacing.Xs, bottom = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
        ) {
            Icon(Icons.Default.AttachFile, null, Modifier.size(Sizes.IconSmall))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatFileSize(size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            IconButton(onRemove, Modifier.size(Sizes.TouchTarget)) { Icon(Icons.Default.Close, strings.removeAttachment) }
        }
    }
}

@Composable
private fun AgentSnippetSheet(
    repository: HostRepository,
    onUse: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings = remember(repository) { repository.loadSettings() }
    val theme = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.DEFAULT }
    SnippetInsertSheet(
        repository = repository,
        theme = theme,
        onUse = { content, _ ->
            onUse(content)
            onDismiss()
        },
        onDismiss = onDismiss,
        allowRun = false,
    )
}

private fun appendSnippet(
    current: String,
    snippet: String,
): String =
    when {
        current.isBlank() -> snippet
        current.endsWith('\n') -> current + snippet
        else -> "$current\n$snippet"
    }

@Composable
private fun AgentTurn(
    turn: AgentTurnUi,
    showThinking: Boolean,
    expandTools: Boolean,
    fallbackAgentId: String,
    fallbackAgentLabel: String,
) {
    val blocks = remember(turn.events, showThinking) { buildAgentTurnBlocks(turn.events, showThinking) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
        turn.user?.let { UserMessage(it) }
        if (blocks.isNotEmpty() || turn.running) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentAvatar(turn.events.firstNotNullOfOrNull { it.agent } ?: fallbackAgentId)
                    Text(
                        fallbackAgentLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    blocks.forEachIndexed { index, block ->
                        when (block) {
                            is AgentActivityBlock ->
                                ProcessTimeline(
                                    groupId = "${turn.id}:$index",
                                    activities = block.activities,
                                    turnRunning = turn.running && index == blocks.lastIndex,
                                    expandTools = expandTools,
                                )
                            is AgentMessageBlock -> {
                                val message = block.message
                                if (message.role == "error") {
                                    ErrorMessage(message)
                                } else {
                                    AssistantMessage(message, turn.running)
                                }
                            }
                        }
                    }
                    if (turn.running) AgentTypingIndicator()
                }
            }
        }
    }
}

@Composable
private fun UserMessage(message: AgentChatMessage) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * Sizes.AgentUserBubbleMaxWidthFraction
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(Corners.Lg),
            ) {
                Column(Modifier.padding(Spacing.Md), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    message.attachments.forEach { AttachmentReference(it) }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: AgentChatMessage,
    animateText: Boolean,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph)
    val responseTypography =
        markdownTypography(
            h1 = MaterialTheme.typography.headlineSmall,
            h2 = MaterialTheme.typography.titleLarge,
            h3 = MaterialTheme.typography.titleMedium,
            h4 = MaterialTheme.typography.titleSmall,
            h5 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
            h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            text = bodyStyle,
            paragraph = bodyStyle,
            ordered = bodyStyle,
            bullet = bodyStyle,
            list = bodyStyle,
            table = MaterialTheme.typography.bodyMedium,
            code = MaterialTheme.typography.bodyMedium.copy(fontFamily = monospaceFontFamily()),
            inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = monospaceFontFamily()),
        )
    val markdownComponents =
        remember {
            markdownComponents(
                codeBlock = highlightedCodeBlock,
                codeFence = highlightedCodeFence,
                paragraph = {
                    MarkdownParagraph(
                        content = it.content,
                        node = it.node,
                        modifier = Modifier.fillMaxWidth(),
                        style = it.typography.paragraph,
                    )
                },
            )
        }
    val visibleText = progressiveAgentText(message, animateText)
    val markdownState = rememberMarkdownState(visibleText)
    val parsedMarkdownState by markdownState.state.collectAsState()
    val renderKey = message.id.ifBlank { "${message.role}:${message.activityId}:${message.createdAt}" }
    var retainedMarkdownState by remember(renderKey) { mutableStateOf<MarkdownRenderState>(parsedMarkdownState) }
    LaunchedEffect(parsedMarkdownState) {
        if (parsedMarkdownState !is MarkdownRenderState.Loading) retainedMarkdownState = parsedMarkdownState
    }
    Markdown(
        state = retainedMarkdownState,
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        components = markdownComponents,
        typography = responseTypography,
    )
}

@Composable
private fun progressiveAgentText(
    message: AgentChatMessage,
    animateOnArrival: Boolean,
): String {
    val key = message.id.ifBlank { "${message.role}:${message.activityId}:${message.createdAt}" }
    val animateInitially = remember(key) { animateOnArrival }
    val latestText = rememberUpdatedState(message.text)
    val latestAnimate = rememberUpdatedState(animateOnArrival)
    var visibleText by remember(key) { mutableStateOf(if (animateInitially) "" else message.text) }
    LaunchedEffect(key) {
        var lastUpdateAt = 0L
        while (true) {
            val frameAt = withFrameMillis { it }
            val target = latestText.value
            if (!target.startsWith(visibleText)) visibleText = ""
            if (visibleText.length < target.length && frameAt - lastUpdateAt >= TYPEWRITER_FRAME_INTERVAL_MILLIS) {
                val remaining = target.length - visibleText.length
                val end = nextTypewriterIndex(target, visibleText.length, typewriterCharsPerFrame(remaining))
                visibleText = target.substring(0, end)
                lastUpdateAt = frameAt
            } else if (!latestAnimate.value && visibleText.length >= target.length) {
                break
            }
        }
    }
    return visibleText
}

@Composable
private fun ProcessTimeline(
    groupId: String,
    activities: List<AgentChatMessage>,
    turnRunning: Boolean,
    expandTools: Boolean,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val failedToolCount = activities.count { it.role == "tool" && it.isError }
    val running = turnRunning || activities.any { it.running }
    var expanded by remember(groupId) { mutableStateOf(expandTools) }
    val toolCount = activities.count { it.role == "tool" }
    val status =
        when {
            running && toolCount == 0 && activities.any { it.role == "thinking" } -> strings.thinking
            running -> strings.processRunning
            else -> strings.processCompleted
        }
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Corners.Md),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin)
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        Modifier.size(Sizes.IconSmall),
                        StatusColors.Connected,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(status, style = MaterialTheme.typography.labelLarge)
                    Text(
                        buildString {
                            append(strings.stepCount(activities.size))
                            if (toolCount > 0) append(" · ${strings.toolCount(toolCount)}")
                            if (failedToolCount > 0) append(" · ${strings.failedToolCount(failedToolCount)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    activities.forEach { activity ->
                        TimelineActivity(activity, turnRunning, expandTools)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineActivity(
    message: AgentChatMessage,
    animateText: Boolean,
    expandTools: Boolean,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val hasDetails = message.role != "thinking" || message.text.isNotBlank()
    val inputPreview =
        message.toolInput
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    var expanded by remember(message.id, message.activityId) {
        mutableStateOf(expandTools && message.role == "tool")
    }
    val status =
        if (message.running) {
            strings.toolRunning
        } else if (message.isError) {
            strings.toolFailed
        } else {
            strings.toolCompleted
        }
    val statusColor =
        if (message.isError) {
            MaterialTheme.colorScheme.error
        } else if (message.running) {
            StatusColors.Warning
        } else {
            StatusColors.Connected
        }
    val iconContainerColor =
        when {
            message.isError -> MaterialTheme.colorScheme.errorContainer
            message.role == "thinking" -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
    val iconColor =
        when {
            message.isError -> MaterialTheme.colorScheme.error
            message.role == "thinking" -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Corners.Md),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier)
                    .padding(Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Surface(
                    Modifier.size(Sizes.AgentActivityIconContainer),
                    color = iconContainerColor,
                    shape = RoundedCornerShape(Corners.Sm),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (message.role == "tool") Icons.Default.Terminal else Icons.Default.SmartToy,
                            null,
                            Modifier.size(Sizes.IconSmall),
                            tint = iconColor,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                    Text(
                        if (message.role == "thinking") strings.thinking else message.toolName ?: strings.tool,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (message.role == "tool" && inputPreview.isNotBlank()) {
                        Text(
                            inputPreview,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = monospaceFontFamily(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (hasDetails) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        Modifier.size(Sizes.IconSmall),
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (message.role == "thinking") {
                    Text(
                        progressiveAgentText(message, animateText),
                        Modifier.padding(Spacing.Md),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ToolDetails(message)
                }
            }
        }
    }
}

@Composable
private fun AgentTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "agent-typing")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Corners.Full),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val alpha =
                    transition.animateFloat(
                        initialValue = TYPING_DOT_IDLE_ALPHA,
                        targetValue = 1f,
                        animationSpec =
                            infiniteRepeatable(
                                animation =
                                    tween(
                                        durationMillis = TYPING_DOT_DURATION_MILLIS,
                                        delayMillis = index * TYPING_DOT_STAGGER_MILLIS,
                                    ),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "agent-typing-dot-$index",
                    )
                Box(
                    Modifier
                        .size(Sizes.AgentTypingDot)
                        .alpha(alpha.value)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(Corners.Full)),
                )
            }
        }
    }
}

@Composable
private fun ToolDetails(message: AgentChatMessage) {
    val strings = LocalAppStrings.current.nativeAgents
    val input = message.toolInput.orEmpty().trim()
    val output = message.text.trim()
    if (input.isBlank() && output.isBlank()) return
    Column(
        Modifier.fillMaxWidth().padding(Spacing.Md),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        if (input.isNotBlank()) ToolDetailSection(strings.toolInputLabel, input, false)
        if (output.isNotBlank()) ToolDetailSection(strings.toolOutputLabel, output, message.isError)
    }
}

@Composable
private fun ToolDetailSection(
    label: String,
    content: String,
    isError: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Corners.Sm)) {
            Text(
                content,
                Modifier.fillMaxWidth().padding(Spacing.Sm),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = monospaceFontFamily(),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = TOOL_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun nextTypewriterIndex(
    text: String,
    current: Int,
    charsPerFrame: Int,
): Int {
    var next = (current + charsPerFrame).coerceAtMost(text.length)
    if (next in 1 until text.length && text[next - 1].isHighSurrogate() && text[next].isLowSurrogate()) next++
    return next
}

internal fun typewriterCharsPerFrame(remaining: Int): Int =
    when {
        remaining > 1_200 -> 32
        remaining > 600 -> 16
        remaining > 240 -> 8
        remaining > 80 -> 4
        else -> 2
    }

@Composable
private fun ErrorMessage(message: AgentChatMessage) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(Corners.Md)) {
        Row(Modifier.fillMaxWidth().padding(Spacing.Md), horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
            Text(message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AttachmentReference(attachment: AgentAttachment) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Icon(Icons.Default.AttachFile, null, Modifier.size(Sizes.IconSmall))
        Text(attachment.name, style = MaterialTheme.typography.labelSmall)
        Text(formatFileSize(attachment.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerSheet(
    controller: AgentBridgeController,
    initialPath: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var currentPath by remember { mutableStateOf(initialPath) }
    var directories by remember { mutableStateOf<List<AgentDirectory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(path: String?) {
        loading = true
        error = null
        controller.browseDirectories(path) { resolved, entries, failure ->
            currentPath = resolved
            directories = entries
            error = failure
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(initialPath.ifBlank { null }) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().height(Sizes.AgentDirectorySheet).navigationBarsPadding()) {
            Text(strings.selectDirectory, Modifier.padding(horizontal = Spacing.Lg), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                currentPath,
                Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (currentPath != "/" && currentPath.isNotBlank()) {
                    item {
                        ListItem(
                            { Text(strings.parentDirectory) },
                            leadingContent = { Icon(Icons.Default.KeyboardArrowUp, null) },
                            modifier = Modifier.clickable { load(parentRemotePath(currentPath)) },
                        )
                    }
                }
                items(directories, key = { it.path }) { directory ->
                    ListItem(
                        { Text(directory.name) },
                        leadingContent = { Icon(Icons.Default.Folder, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                        modifier = Modifier.clickable { load(directory.path) },
                    )
                }
                if (!loading && directories.isEmpty() && error == null) {
                    item { Text(strings.folderEmpty, Modifier.padding(Spacing.Lg), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                error?.let { message -> item { Text(message, Modifier.padding(Spacing.Lg), color = MaterialTheme.colorScheme.error) } }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(Spacing.Lg))
            Button(
                { onSelected(currentPath) },
                Modifier.fillMaxWidth().padding(Spacing.Lg),
                enabled = currentPath.isNotBlank() && !loading,
            ) { Text(strings.useThisDirectory) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionsSheet(
    session: AgentBridgeSessionInfo,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var title by remember(session.id) { mutableStateOf(session.title) }
    var confirmDelete by remember(session.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            Text(strings.sessionActions, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                title,
                { title = it },
                Modifier.fillMaxWidth(),
                label = { Text(strings.renameSession) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Edit, null) },
            )
            Button({ onRename(title) }, Modifier.fillMaxWidth(), enabled = title.isNotBlank()) { Text(strings.save) }
            if (confirmDelete) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(Corners.Md)) {
                    Column(Modifier.fillMaxWidth().padding(Spacing.Md), verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                        Text(strings.deleteSessionConfirm, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(
                            onDelete,
                            Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text(strings.deleteSession)
                        }
                    }
                }
            } else {
                TextButton({ confirmDelete = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(Spacing.Sm))
                    Text(strings.deleteSession, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(Spacing.Md))
        }
    }
}

private fun PickedFile.toAgentAttachment(): AgentPendingAttachment = AgentPendingAttachment(name, size, readChunk)

private fun parentRemotePath(path: String): String {
    val normalized = path.trimEnd('/')
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
    return parent.ifBlank { "/" }
}

private fun formatFileSize(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }
