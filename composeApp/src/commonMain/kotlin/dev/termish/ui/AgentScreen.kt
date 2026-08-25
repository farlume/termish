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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.termish.agent.AgentApprovalRequest
import dev.termish.agent.AgentArtifact
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
import dev.termish.data.AppSettings
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.agentProviderKeyAccount
import dev.termish.data.asrKeyAccount
import dev.termish.data.newId
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.agent_claude
import dev.termish.generated.resources.agent_codex
import dev.termish.generated.resources.agent_gemini
import dev.termish.generated.resources.agent_opencode
import dev.termish.generated.resources.agent_pi
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SftpSession
import dev.termish.ssh.SshCallbacks
import dev.termish.ui.theme.AgentBrandColors
import dev.termish.ui.theme.Corners
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.Spacing
import dev.termish.ui.theme.StatusColors
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.ioDispatcher
import dev.termish.util.monospaceFontFamily
import dev.termish.voice.AsrEngine
import dev.termish.voice.MicrophoneRecorder
import dev.termish.voice.createAsrEngine
import dev.termish.voice.rememberMicPermissionRequester
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

private const val TYPING_DOT_DURATION_MILLIS = 480
private const val TYPING_DOT_STAGGER_MILLIS = 120
private const val TYPING_DOT_IDLE_ALPHA = 0.28f
private const val TYPEWRITER_FRAME_INTERVAL_MILLIS = 24L

/** 工具输出完整展示上限（防超大输出刷爆列表）。 */
private const val TOOL_PREVIEW_MAX_LINES = 5

internal fun agentSlashCommands(
    @Suppress("UNUSED_PARAMETER") agentId: String,
): List<String> = listOf("/new", "/model", "/status", "/settings", "/stop", "/help")

internal enum class AgentSlashAction { NEW, MODEL, STATUS, SETTINGS, STOP, HELP, UNSUPPORTED }

internal fun agentSlashAction(input: String): AgentSlashAction =
    when (input.trim().substringBefore(' ').lowercase()) {
        "/new", "/clear" -> AgentSlashAction.NEW
        "/model", "/models" -> AgentSlashAction.MODEL
        "/status" -> AgentSlashAction.STATUS
        "/settings" -> AgentSlashAction.SETTINGS
        "/stop" -> AgentSlashAction.STOP
        "/help" -> AgentSlashAction.HELP
        else -> AgentSlashAction.UNSUPPORTED
    }

internal fun slashCommandSuggestions(
    agentId: String,
    input: String,
): List<String> {
    if (!input.startsWith("/") || input.drop(1).any(Char::isWhitespace)) return emptyList()
    return agentSlashCommands(agentId).filter { it.startsWith(input, ignoreCase = true) }
}

internal fun mergeAgentVoiceDraft(
    draft: String,
    transcript: String,
): String {
    val spoken = transcript.trim()
    if (spoken.isEmpty()) return draft
    if (draft.isEmpty() || draft.last().isWhitespace()) return draft + spoken
    return "$draft $spoken"
}

private enum class AgentWorkspacePage { CHAT, AGENTS, SETTINGS }

private enum class AgentSlashDialog { MODEL, STATUS, HELP, ERROR }

@OptIn(ExperimentalResourceApi::class)
@Composable
fun AgentScreen(
    host: Host,
    repository: HostRepository,
    onBack: () -> Unit,
    /** 屏幕远控会话条目（AppRoot 持有；非空 = Agent 页内全屏播放远程画面）。 */
    screenEntry: ScreenSessionEntry? = null,
    /** 建立屏幕推流会话（复用终端页 establishScreen 流程）。 */
    onStartScreen: (Host) -> Unit = {},
    /** 关闭屏幕会话并移除条目。 */
    onCloseScreen: (Host) -> Unit = {},
    /** 屏幕断线重连（重建推流会话）。 */
    onReconnectScreen: (Host) -> Unit = {},
    /** 屏幕推流服务安装引导（缺 ffmpeg / relay 时一键安装）。 */
    onInstallScreenService: (Host, String?) -> Unit = { _, _ -> },
    /** 全屏帧率/画质档位切换。 */
    onScreenConfigChange: (Host, Int, String) -> Unit = { _, _, _ -> },
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
                    return awaitAuthPromptAnswer(request.deferred).also {
                        if (authRequest === request) authRequest = null
                    }
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    val known = repository.getHost(host.id)?.knownHostFingerprint ?: host.knownHostFingerprint
                    if (known == hostKey.fingerprintSha256) return true
                    if (known == null && !repository.loadSettings().verifyHostKeyOnFirstUse) return true
                    val request = HostKeyRequest(hostKey, known != null, known)
                    hostKeyRequest = request
                    val accepted = awaitHostKeyPromptAnswer(request.deferred)
                    if (hostKeyRequest === request) hostKeyRequest = null
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
            }
        }
    val controller = remember(host.id) { AgentBridgeController(host, repository, callbacks, scope) }

    LaunchedEffect(controller) { controller.connect() }
    DisposableEffect(controller) {
        onDispose {
            authRequest?.deferred?.complete(null)
            authRequest = null
            hostKeyRequest?.deferred?.complete(false)
            hostKeyRequest = null
            controller.close()
        }
    }
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
        screenEntry = screenEntry,
        onStartScreen = onStartScreen,
        onCloseScreen = onCloseScreen,
        onReconnectScreen = onReconnectScreen,
        onInstallScreenService = onInstallScreenService,
        onScreenConfigChange = onScreenConfigChange,
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
    screenEntry: ScreenSessionEntry? = null,
    onStartScreen: (Host) -> Unit = {},
    onCloseScreen: (Host) -> Unit = {},
    onReconnectScreen: (Host) -> Unit = {},
    onInstallScreenService: (Host, String?) -> Unit = { _, _ -> },
    onScreenConfigChange: (Host, Int, String) -> Unit = { _, _, _ -> },
) {
    AgentImeResizeEffect()
    val strings = LocalAppStrings.current.nativeAgents
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    var page by remember { mutableStateOf(AgentWorkspacePage.CHAT) }
    var preferences by remember { mutableStateOf(initialPreferences) }
    var selectedAgent by remember { mutableStateOf(initialPreferences.defaultAgent) }
    var directoryPickerOpen by remember { mutableStateOf(false) }
    var sessionActions by remember { mutableStateOf<AgentBridgeSessionInfo?>(null) }
    var nativeHistoryOpen by remember { mutableStateOf(false) }
    val appSettings by repository.appSettings.collectAsState()
    val providers = appSettings.agentProviders
    val voiceSettings = appSettings
    var editingProvider by remember { mutableStateOf<AgentProvider?>(null) }
    var addingProvider by remember { mutableStateOf(false) }

    // ---- 屏幕远控：沉浸式（隐藏状态栏）+ 状态栏高度记录（header 内容下移对齐）----
    val screenDensity = LocalDensity.current
    var lastStatusBarTop by remember { mutableIntStateOf(0) }
    val screenActive = screenEntry?.session != null
    if (!screenActive) {
        lastStatusBarTop = maxOf(lastStatusBarTop, WindowInsets.statusBars.getTop(screenDensity))
    }
    PlatformImmersiveMode(screenActive)

    // ---- 终端「+」工具菜单（右上角 ⋮）----
    var gitOpen by remember { mutableStateOf(false) }
    var uploadDirDialog by remember { mutableStateOf(false) }
    var uploadTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploadTargetDir by remember { mutableStateOf<String?>(null) }
    var fileBrowserOpen by remember { mutableStateOf(false) }
    var fileBrowserStartPath by remember { mutableStateOf<String?>(null) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var favoritePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    val uploader = remember(controller) { AgentUploader(controller, scope) }
    val uploadState by rememberUpdatedState(uploader.state)
    val pickUploadFiles =
        rememberFilePicker { file ->
            uploader.enqueue(file, uploadTargetDir.orEmpty().ifBlank { "/tmp" })
        }
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
        repository.updateSettings { current -> current.copy(agentProviders = value) }
    }

    fun providerRuntime(
        agentId: String,
        providerId: String?,
    ): AgentProviderRuntime? {
        val provider = providers.firstOrNull { it.id == providerId && it.enabled } ?: return null
        if (!providerSupportsAgent(provider, agentId)) return null
        return AgentProviderRuntime(
            id = provider.id,
            type = provider.type.name.lowercase(),
            apiKey = SecretStore.get(SECRET_SERVICE, agentProviderKeyAccount(provider.id)).orEmpty(),
            baseUrl = provider.baseUrl,
            anthropicBaseUrl = provider.anthropicBaseUrl,
            piProvider = provider.piProvider,
        )
    }

    // 终端「+」工具菜单（右上角 ⋮）：文件管理 / 上传 / Git / 收藏夹 / 屏幕远控。
    // 与终端页同源同义：文件管理定位到 Agent 会话工作目录；上传目标 = Agent
    // cwd + /tmp；Git 走 Agent 控制连接独立 exec 通道；屏幕复用推流基础设施。
    // 每次重组重建（5 项成本可忽略），保证语言切换即时生效。
    val all = LocalAppStrings.current
    val toolActions =
        listOf(
            AgentToolAction(
                label = all.upload.fileManagerLabel,
                icon = Icons.Filled.FolderOpen,
                onClick = {
                    fileBrowserStartPath = controller.currentSession?.cwd
                    fileBrowserOpen = true
                },
            ),
            AgentToolAction(
                label = all.upload.menuLabel,
                icon = Icons.Filled.Upload,
                onClick = {
                    uploadTargets =
                        buildList {
                            controller.currentSession
                                ?.cwd
                                ?.takeIf { it.isNotBlank() }
                                ?.let { add(it) }
                            add("/tmp")
                        }
                    uploadDirDialog = true
                },
            ),
            AgentToolAction(
                label = all.git.menuLabel,
                icon = Icons.Filled.AccountTree,
                onClick = {
                    gitOpen = true
                },
            ),
            AgentToolAction(
                label = all.sftpExt.favoritesTitle,
                icon = Icons.Filled.Star,
                onClick = {
                    val favs = repository.loadFavorites(controller.host.id)
                    if (favs.isEmpty()) {
                        scope.launch { snackbar.showSnackbar(all.sftpExt.favoritesEmpty) }
                    } else {
                        favoritePaths = favs
                        favoritesOpen = true
                    }
                },
            ),
            AgentToolAction(
                label = all.screen.menuLabel,
                icon = Icons.Filled.Monitor,
                onClick = {
                    // 已有会话 = 正在播放（覆盖层已显示）；没有才建立
                    if (screenEntry == null) onStartScreen(controller.host)
                },
            ),
        )

    // 上传完成/失败提示（监听状态变化，与终端页同文案）
    LaunchedEffect(uploadState) {
        when (val st = uploadState) {
            is AgentUploadUiState.Done -> {
                if (st.count > 0) snackbar.showSnackbar(all.upload.done(st.count))
                uploader.clear()
            }
            is AgentUploadUiState.Failed -> {
                snackbar.showSnackbar(all.upload.failed(st.message))
                uploader.clear()
            }
            else -> {}
        }
    }

    PlatformBackHandler(enabled = canReturnToAgentHome) { controller.newChat() }

    // 屏幕远控全屏：系统返回键优先关闭屏幕。BackHandler 后注册先触发——
    // 必须放在其他 handler（newChat / AppRoot 全局回首页）之后注册才能抢到；
    // 否则全屏时按返回会跑到首页（用户反馈）
    if (screenActive) {
        PlatformBackHandler(enabled = true) {
            screenEntry?.let { onCloseScreen(it.host) }
        }
    }

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
                onNativeHistory = {
                    nativeHistoryOpen = true
                    scope.launch { drawerState.close() }
                },
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
                                            voiceSettings,
                                            { directoryPickerOpen = true },
                                            { page = AgentWorkspacePage.SETTINGS },
                                            { message -> scope.launch { snackbar.showSnackbar(message) } },
                                        )
                                    } else {
                                        val session = controller.currentSession
                                        AgentChat(
                                            controller,
                                            preferences,
                                            providers.firstOrNull { it.id == session?.provider },
                                            providerRuntime(session?.agent.orEmpty(), session?.provider),
                                            voiceSettings,
                                            onOpenSettings = { page = AgentWorkspacePage.SETTINGS },
                                            onOpenArtifact = { artifact ->
                                                fileBrowserStartPath = parentRemotePath(artifact.remotePath)
                                                fileBrowserOpen = true
                                            },
                                            onFeedback = { message -> scope.launch { snackbar.showSnackbar(message) } },
                                        )
                                    }
                            }
                    }
                }
                AgentFloatingChrome(
                    controller = controller,
                    onMenu = { scope.launch { drawerState.open() } },
                    toolActions = toolActions,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                // 上传进度浮层（右上角 ⋮ → 上传）：与终端页同款卡片，置于顶部 chrome 下方
                (uploadState as? AgentUploadUiState.Uploading)?.let { up ->
                    Surface(
                        Modifier.align(Alignment.TopCenter).padding(top = Sizes.AgentChromeClearance + Sizes.TouchTarget),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = Spacing.Xs,
                        shape = RoundedCornerShape(Corners.Lg),
                        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                        ) {
                            CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin)
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                                Text(up.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                Text(
                                    LocalAppStrings.current.upload.uploading("${up.doneCount + 1}/${up.totalCount}"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (up.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (up.sent.toFloat() / up.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.widthIn(min = Sizes.IconMedium).weight(1f, fill = false),
                                )
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Spacing.Md),
                )
            }
        }
    }

    // 屏幕远控全屏覆盖：AppRoot 建好会话条目后在此全屏播放（Agent 连接不中断）。
    // 放在根层（ModalNavigationDrawer 之后、最后声明）：与终端页全屏同构——
    // 若嵌在抽屉内容里，视频手势层会吃掉 header 返回/模式按钮的触摸。
    screenEntry?.session?.let { screenSession ->
        ScreenContent(
            host = screenEntry.host,
            session = screenSession,
            state = screenEntry.uiState,
            onBack = { onCloseScreen(screenEntry.host) },
            onReconnect = { onReconnectScreen(screenEntry.host) },
            onInstallService = { password -> onInstallScreenService(screenEntry.host, password) },
            onClose = { onCloseScreen(screenEntry.host) },
            statusBarInsetTop = lastStatusBarTop,
            onStreamConfigChange = { fps, scale -> onScreenConfigChange(screenEntry.host, fps, scale) },
            modifier = Modifier.fillMaxSize(),
        )
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
    if (nativeHistoryOpen) {
        NativeHistorySheet(
            controller = controller,
            onDismiss = { nativeHistoryOpen = false },
            onOpen = { native ->
                controller.importNativeHistory(native) { success ->
                    if (success) {
                        nativeHistoryOpen = false
                        page = AgentWorkspacePage.CHAT
                    } else {
                        controller.errorMessage?.let { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    }
                }
            },
        )
    }
    if (addingProvider || editingProvider != null) {
        AgentProviderDialog(
            controller = controller,
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

    // ---- 终端「+」工具：Git 面板（Agent 控制连接 + 会话 cwd）----
    if (gitOpen) {
        val gitTheme =
            TerminalThemes.ALL.getOrElse(repository.loadSettings().terminalThemeIndex) {
                TerminalThemes.DEFAULT
            }
        GitOverlay(
            connected = controller.state == AgentBridgeState.READY,
            inAltScreen = false,
            runner = remember(controller) { AgentGitCommandRunner(controller) },
            theme = gitTheme,
            open = gitOpen,
            onOpenChange = { gitOpen = it },
            bottomInset = Spacing.None,
            onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // ---- 终端「+」工具：上传目标目录选择（Agent 工作目录 / /tmp）----
    if (uploadDirDialog) {
        val all = LocalAppStrings.current
        AlertDialog(
            onDismissRequest = { uploadDirDialog = false },
            title = { Text(all.upload.dirTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    uploadTargets.forEach { dir ->
                        UploadDirOptionCard(
                            icon = Icons.Filled.FolderOpen,
                            title = if (dir == "/tmp") all.upload.dirTmp else all.upload.dirCurrent,
                            subtitle = dir,
                            onClick = {
                                uploadDirDialog = false
                                uploadTargetDir = dir
                                pickUploadFiles()
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { uploadDirDialog = false }) { Text(all.terminalCancel) }
            },
        )
    }

    // ---- 终端「+」工具：文件管理（复用终端页完整 SFTP，Agent 会话不中断）----
    if (fileBrowserOpen) {
        AgentSftpOverlay(
            controller = controller,
            repository = repository,
            initialPath = fileBrowserStartPath,
            ownerScope = scope,
            onDismiss = { fileBrowserOpen = false },
        )
    }

    // ---- 终端「+」工具：收藏夹（与终端页同款 AlertDialog，点击直达文件管理）----
    if (favoritesOpen) {
        SftpFavoritesDialog(
            favorites = favoritePaths,
            onOpen = { favorite ->
                favoritesOpen = false
                fileBrowserStartPath = favorite
                fileBrowserOpen = true
            },
            onRemove = { favorite ->
                favoritePaths = favoritePaths - favorite
                repository.saveFavorites(controller.host.id, favoritePaths)
            },
            onDismiss = { favoritesOpen = false },
        )
    }

    controller.pendingApprovals.firstOrNull()?.let { approval ->
        AgentApprovalDialog(
            approval = approval,
            onRespond = { decision, value -> controller.respondApproval(approval, decision, value) },
        )
    }
}

@Composable
private fun AgentApprovalDialog(
    approval: AgentApprovalRequest,
    onRespond: (String, String?) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val isInput = approval.kind == "input" || approval.kind == "editor"
    val isSelect = approval.kind == "select"
    var response by rememberSaveable(approval.id) { mutableStateOf(approval.prefill) }
    val title =
        when (approval.kind) {
            "command" -> strings.approvalCommand
            "network" -> strings.approvalNetwork
            "file_change" -> strings.approvalFileChange
            "permissions" -> strings.approvalPermissions
            else -> approval.title.ifBlank { strings.approvalRequired }
        }

    AlertDialog(
        onDismissRequest = { onRespond("cancel", null) },
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = Sizes.AgentDirectorySheet).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                approval.message.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                approval.command.takeIf { it.isNotBlank() }?.let { command ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(Corners.Sm),
                    ) {
                        Text(
                            command,
                            Modifier.fillMaxWidth().padding(Spacing.Md),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                approval.cwd.takeIf { it.isNotBlank() }?.let { cwd ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        Text(
                            strings.approvalWorkingDirectory,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            cwd,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                approval.details.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = monospaceFontFamily(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelect) {
                    approval.options.forEach { option ->
                        OutlinedButton(
                            onClick = { onRespond("select", option) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(option)
                        }
                    }
                    if (approval.allowCustom) {
                        OutlinedTextField(
                            value = response,
                            onValueChange = { response = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(strings.approvalOther) },
                            visualTransformation =
                                if (approval.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            maxLines = 3,
                        )
                    }
                }
                if (isInput) {
                    OutlinedTextField(
                        value = response,
                        onValueChange = { response = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = approval.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        visualTransformation =
                            if (approval.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        minLines = if (approval.kind == "editor") 4 else 1,
                        maxLines = if (approval.kind == "editor") 8 else 3,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onRespond(
                        if (isInput || isSelect || "deny" !in approval.options) "cancel" else "deny",
                        null,
                    )
                },
            ) {
                Text(
                    if (isInput || isSelect || "deny" !in approval.options) {
                        strings.approvalCancel
                    } else {
                        strings.approvalDeny
                    },
                )
            }
        },
        confirmButton = {
            when {
                isSelect && approval.allowCustom ->
                    Button(
                        onClick = { onRespond("select", response) },
                        enabled = response.isNotBlank(),
                    ) {
                        Text(strings.approvalSubmit)
                    }
                isSelect -> Unit
                isInput -> Button(onClick = { onRespond("submit", response) }) { Text(strings.approvalSubmit) }
                "allow_once" in approval.options ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        if ("allow_session" in approval.options) {
                            TextButton(onClick = { onRespond("allow_session", null) }) {
                                Text(strings.approvalAllowSession)
                            }
                        }
                        Button(onClick = { onRespond("allow_once", null) }) {
                            Text(strings.approvalAllowOnce)
                        }
                    }
                else -> Unit
            }
        },
    )
}

@Composable
private fun AgentDrawer(
    controller: AgentBridgeController,
    page: AgentWorkspacePage,
    onNewChat: () -> Unit,
    onSelectSession: (AgentBridgeSessionInfo) -> Unit,
    onSessionActions: (AgentBridgeSessionInfo) -> Unit,
    onNativeHistory: () -> Unit,
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
        NavigationDrawerItem(
            label = { Text(strings.nativeHistory) },
            selected = false,
            icon = { Icon(Icons.Default.Download, null) },
            onClick = onNativeHistory,
            modifier = Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        )
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
    onMenu: () -> Unit,
    toolActions: List<AgentToolAction>,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var toolMenuOpen by remember { mutableStateOf(false) }
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
            // 终端「+」工具菜单（右上角 ⋮）：Bridge 就绪即显示（不依赖当前会话），
            // 会话改名/删除已收进抽屉的会话 ⋮（原右上角入口移除）
            if (controller.state == AgentBridgeState.READY) {
                Box {
                    IconButton({ toolMenuOpen = true }) {
                        Icon(Icons.Default.MoreVert, strings.sessionActions)
                    }
                    DropdownMenu(expanded = toolMenuOpen, onDismissRequest = { toolMenuOpen = false }) {
                        toolActions.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                leadingIcon = { Icon(action.icon, null) },
                                onClick = {
                                    toolMenuOpen = false
                                    action.onClick()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Agent 页工具菜单项（对应终端页「+」菜单）。 */
private data class AgentToolAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

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
    voiceSettings: AppSettings,
    onChooseDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    val pickFiles = rememberFilePicker { attachments = attachments + it }
    val available = controller.agents.filter { it.available && it.supported }
    val providerConfig =
        providers.firstOrNull {
            it.id == preferences.providerByAgent[selectedAgent] && it.enabled && providerSupportsAgent(it, selectedAgent)
        }
    val modelOptions = providerConfig?.models.orEmpty()
    var selectedModel by
        remember(selectedAgent, providerConfig?.id, preferences.defaultModel, modelOptions) {
            mutableStateOf(preferences.defaultModel.ifBlank { modelOptions.firstOrNull().orEmpty() })
        }
    var slashDialog by remember { mutableStateOf<AgentSlashDialog?>(null) }
    var slashError by remember { mutableStateOf("") }

    fun runSlashCommand(command: String) {
        when (agentSlashAction(command)) {
            AgentSlashAction.NEW -> {
                input = ""
                attachments = emptyList()
            }
            AgentSlashAction.MODEL -> slashDialog = AgentSlashDialog.MODEL
            AgentSlashAction.STATUS -> slashDialog = AgentSlashDialog.STATUS
            AgentSlashAction.SETTINGS -> onOpenSettings()
            AgentSlashAction.STOP -> controller.abort()
            AgentSlashAction.HELP -> slashDialog = AgentSlashDialog.HELP
            AgentSlashAction.UNSUPPORTED -> {
                slashError = strings.slashCommandUnsupported(command.substringBefore(' '))
                slashDialog = AgentSlashDialog.ERROR
            }
        }
    }
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
        if (modelOptions.isNotEmpty()) {
            AgentModelSelector(
                selected = selectedModel,
                models = modelOptions,
                onSelect = { selectedModel = it },
            )
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
            providerConfig?.name,
            controller.busy,
            controller.attachmentProgress,
            selectedAgent.isNotBlank(),
            selectedAgent,
            voiceSettings,
            onFeedback,
            ::runSlashCommand,
            {
                controller.startConversation(
                    selectedAgent,
                    preferences.defaultDirectory.ifBlank { null },
                    selectedModel.ifBlank { null },
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
    AgentSlashCommandDialog(
        dialog = slashDialog,
        models = modelOptions,
        selectedModel = selectedModel,
        statusText =
            listOf(
                "${strings.defaultAgent}: ${controller.agents.firstOrNull { it.id == selectedAgent }?.label ?: selectedAgent}",
                "${strings.defaultModel}: ${selectedModel.ifBlank { strings.builtInProvider }}",
                "${strings.defaultDirectory}: ${preferences.defaultDirectory}",
                "${strings.providers}: ${providerConfig?.name ?: strings.builtInProvider}",
            ).joinToString("\n"),
        errorText = slashError,
        onSelectModel = {
            selectedModel = it
            slashDialog = null
        },
        onDismiss = { slashDialog = null },
    )
}

@Composable
private fun AgentModelSelector(
    selected: String,
    models: List<String>,
    onSelect: (String) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.Md)) {
        Surface(
            Modifier.fillMaxWidth().clickable { expanded = true },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(Corners.Md),
        ) {
            Row(
                Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Text(strings.defaultModel, style = MaterialTheme.typography.labelMedium)
                Text(
                    selected,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ExpandMore, null, Modifier.size(Sizes.IconSmall))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.distinct().forEach { model ->
                DropdownMenuItem(
                    text = { Text(model) },
                    onClick = {
                        expanded = false
                        onSelect(model)
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentSlashCommandDialog(
    dialog: AgentSlashDialog?,
    models: List<String>,
    selectedModel: String,
    statusText: String,
    errorText: String,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (dialog == null) return
    val strings = LocalAppStrings.current.nativeAgents
    var customModel by rememberSaveable(dialog, selectedModel) { mutableStateOf(selectedModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (dialog) {
                    AgentSlashDialog.MODEL -> strings.defaultModel
                    AgentSlashDialog.STATUS -> strings.slashCommandStatus
                    AgentSlashDialog.HELP, AgentSlashDialog.ERROR -> strings.slashCommand
                },
            )
        },
        text = {
            when (dialog) {
                AgentSlashDialog.MODEL ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        OutlinedTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.defaultModel) },
                            placeholder = { Text(strings.defaultModelHint) },
                            singleLine = true,
                        )
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = Sizes.AgentSlashMenuMaxHeight)) {
                            item {
                                DropdownMenuItem(
                                    text = { Text(strings.builtInProvider) },
                                    trailingIcon = {
                                        if (selectedModel.isBlank()) Icon(Icons.Default.CheckCircle, null)
                                    },
                                    onClick = { onSelectModel("") },
                                )
                            }
                            items(models.distinct(), key = { it }) { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    trailingIcon = {
                                        if (model == selectedModel) Icon(Icons.Default.CheckCircle, null)
                                    },
                                    onClick = { onSelectModel(model) },
                                )
                            }
                        }
                    }
                AgentSlashDialog.STATUS -> Text(statusText, fontFamily = monospaceFontFamily())
                AgentSlashDialog.HELP -> Text(strings.slashCommandHelp)
                AgentSlashDialog.ERROR -> Text(errorText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            if (dialog == AgentSlashDialog.MODEL) {
                TextButton(onDismiss) { Text(strings.approvalCancel) }
            }
        },
        confirmButton = {
            if (dialog == AgentSlashDialog.MODEL) {
                Button(onClick = { onSelectModel(customModel.trim()) }) { Text(strings.save) }
            } else {
                TextButton(onDismiss) { Text(LocalAppStrings.current.terminalConfirm) }
            }
        },
    )
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
internal fun AgentAvatar(
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
    val compatible = providers.filter { it.enabled && providerSupportsAgent(it, agent.id) }
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
    controller: AgentBridgeController,
    initial: AgentProvider?,
    onDismiss: () -> Unit,
    onSave: (AgentProvider, String) -> Unit,
    onDelete: (AgentProvider) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val providerId = remember(initial?.id) { initial?.id ?: newId() }
    // wire 协议与 Pi CLI provider id 分开保存，避免把 openai 错传给 Pi。
    var type by remember(initial?.id) { mutableStateOf(initial?.type ?: AgentProviderType.OPENAI) }
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl ?: "") }
    var anthropicBaseUrl by remember(initial?.id) { mutableStateOf(initial?.anthropicBaseUrl ?: "") }
    var piProvider by remember(initial?.id) { mutableStateOf(initial?.piProvider ?: "") }
    var models by remember(initial?.id) { mutableStateOf(initial?.models?.joinToString(", ") ?: "") }
    var apiKey by
        remember(initial?.id) {
            mutableStateOf(SecretStore.get(SECRET_SERVICE, agentProviderKeyAccount(providerId)).orEmpty())
        }
    var confirmDelete by remember(initial?.id) { mutableStateOf(false) }
    var presetsOpen by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    fun presetName(preset: AgentProviderPreset): String = strings.providerPresetNames[preset.id] ?: preset.id

    fun applyPreset(preset: AgentProviderPreset) {
        type = preset.type
        name = presetName(preset)
        baseUrl = preset.baseUrl
        anthropicBaseUrl = preset.anthropicBaseUrl
        piProvider = preset.piProvider
        models = preset.models.joinToString(", ")
        presetsOpen = false
    }

    if (!confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (initial == null) strings.addProvider else initial.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                    // 预设一键添加（编辑态不显示）
                    if (initial == null) {
                        Box {
                            OutlinedButton({ presetsOpen = true }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.size(Spacing.Sm))
                                Text(strings.providerPreset)
                            }
                            DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                                AGENT_PROVIDER_PRESETS.forEach { preset ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(presetName(preset))
                                                Text(
                                                    buildString {
                                                        append(if (preset.type == AgentProviderType.ANTHROPIC) "Anthropic" else "OpenAI")
                                                        if (preset.note.isNotBlank()) append(" · ${preset.note}")
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = { applyPreset(preset) },
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerName) },
                        singleLine = true,
                    )
                    // 协议类型：openai / anthropic
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        Text(strings.providerProtocol, style = MaterialTheme.typography.labelLarge)
                        AgentProviderTypeChoice(
                            selected = type,
                            onSelect = { type = it },
                        )
                    }
                    OutlinedTextField(
                        baseUrl,
                        { baseUrl = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerBaseUrl) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        anthropicBaseUrl,
                        { anthropicBaseUrl = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerAnthropicBaseUrl) },
                        supportingText = { Text(strings.providerAnthropicBaseUrlHint) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        piProvider,
                        { piProvider = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(strings.providerPiId) },
                        supportingText = { Text(strings.providerPiIdHint) },
                        singleLine = true,
                    )
                    // 模型列表 + 拉取
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        OutlinedTextField(
                            models,
                            { models = it },
                            Modifier.weight(1f),
                            label = { Text(strings.providerModels) },
                            supportingText = { Text(strings.providerModelsHint) },
                            singleLine = true,
                        )
                        FilledTonalIconButton(
                            onClick = {
                                if (baseUrl.isNotBlank() && apiKey.isNotBlank() && !fetchingModels) {
                                    fetchingModels = true
                                    fetchError = null
                                    controller.fetchProviderModels(
                                        baseUrl.trim().trimEnd('/'),
                                        apiKey.trim(),
                                        type.name.lowercase(),
                                    ) { result ->
                                        fetchingModels = false
                                        result
                                            .onSuccess { list ->
                                                models = list.joinToString(", ")
                                            }.onFailure { e ->
                                                fetchError = e.message
                                            }
                                    }
                                }
                            },
                            enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && !fetchingModels,
                        ) {
                            if (fetchingModels) {
                                CircularProgressIndicator(Modifier.size(Sizes.IconSmall), strokeWidth = Sizes.BorderThin)
                            } else {
                                Icon(Icons.Default.Refresh, strings.providerModelsFetch)
                            }
                        }
                    }
                    fetchError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                                type = type,
                                name = name.trim(),
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                anthropicBaseUrl = anthropicBaseUrl.trim().trimEnd('/'),
                                piProvider = piProvider.trim(),
                                models = models.split(',').map { it.trim() }.filter { it.isNotBlank() },
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

/** 供应商预设（参考 tuiniverse PROVIDER_PRESETS）。 */
private data class AgentProviderPreset(
    val id: String,
    val type: AgentProviderType,
    val baseUrl: String,
    val anthropicBaseUrl: String = "",
    val piProvider: String = "",
    val models: List<String> = emptyList(),
    val note: String = "",
)

private val AGENT_PROVIDER_PRESETS: List<AgentProviderPreset> =
    listOf(
        AgentProviderPreset(
            "deepseek",
            AgentProviderType.OPENAI,
            "https://api.deepseek.com",
            "https://api.deepseek.com/anthropic",
            "deepseek",
            listOf("deepseek-v4-pro", "deepseek-v4-flash"),
        ),
        AgentProviderPreset("openai", AgentProviderType.OPENAI, "https://api.openai.com/v1"),
        AgentProviderPreset("anthropic", AgentProviderType.ANTHROPIC, "https://api.anthropic.com", models = listOf("claude-sonnet-4-5", "claude-opus-4-1")),
        AgentProviderPreset("moonshot", AgentProviderType.OPENAI, "https://api.moonshot.cn/v1"),
        AgentProviderPreset("zhipu", AgentProviderType.OPENAI, "https://open.bigmodel.cn/api/paas/v4"),
        AgentProviderPreset("qwen", AgentProviderType.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        AgentProviderPreset("doubao", AgentProviderType.OPENAI, "https://ark.cn-beijing.volces.com/api/v3"),
        AgentProviderPreset("gemini", AgentProviderType.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai/"),
        AgentProviderPreset("groq", AgentProviderType.OPENAI, "https://api.groq.com/openai/v1"),
        AgentProviderPreset("xai", AgentProviderType.OPENAI, "https://api.x.ai/v1"),
        AgentProviderPreset("siliconflow", AgentProviderType.OPENAI, "https://api.siliconflow.cn/v1"),
    )

@Composable
private fun AgentProviderTypeChoice(
    selected: AgentProviderType,
    onSelect: (AgentProviderType) -> Unit,
) {
    val options =
        listOf(
            AgentProviderType.OPENAI to "OpenAI",
            AgentProviderType.ANTHROPIC to "Anthropic",
        )
    options.forEach { (option, label) ->
        FilterChip(
            selected = selected == option,
            onClick = { onSelect(option) },
            label = { Text(label) },
            modifier = Modifier.padding(end = Spacing.Xs),
        )
    }
}

private fun providerSupportsAgent(
    provider: AgentProvider,
    agentId: String,
): Boolean {
    val hasAnthropicEndpoint =
        provider.type == AgentProviderType.ANTHROPIC ||
            provider.anthropicBaseUrl.isNotBlank() ||
            provider.type == AgentProviderType.DEEPSEEK
    val isOpenaiCompatible =
        provider.type == AgentProviderType.OPENAI || provider.type == AgentProviderType.DEEPSEEK
    return when (agentId) {
        "claude" -> hasAnthropicEndpoint
        "opencode" -> isOpenaiCompatible
        "pi" -> provider.piProvider.isNotBlank() || provider.type == AgentProviderType.DEEPSEEK
        else -> false
    }
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

internal fun collectAgentArtifacts(events: List<AgentChatMessage>): List<AgentArtifact> =
    events
        .asSequence()
        .flatMap { it.artifacts.asSequence() }
        .distinctBy { it.remotePath }
        .toList()

internal fun shouldAnimateAgentTurn(
    turnId: String,
    activeLocalTurnId: String?,
): Boolean = activeLocalTurnId != null && turnId == activeLocalTurnId

internal fun shouldResumeAgentOutputFollow(
    isScrollInProgress: Boolean,
    canScrollForward: Boolean,
): Boolean = !isScrollInProgress && !canScrollForward

@Composable
private fun AgentChat(
    controller: AgentBridgeController,
    preferences: AgentWorkspacePreferences,
    providerConfig: AgentProvider?,
    provider: AgentProviderRuntime?,
    voiceSettings: AppSettings,
    onOpenSettings: () -> Unit,
    onOpenArtifact: (AgentArtifact) -> Unit,
    onFeedback: (String) -> Unit,
) {
    var input by remember(controller.currentSession?.id) { mutableStateOf("") }
    var attachments by remember(controller.currentSession?.id) { mutableStateOf<List<PickedFile>>(emptyList()) }
    val pickFiles = rememberFilePicker { attachments = attachments + it }
    val strings = LocalAppStrings.current.nativeAgents
    val scope = rememberCoroutineScope()
    val modelOptions = providerConfig?.models.orEmpty()
    var slashDialog by remember(controller.currentSession?.id) { mutableStateOf<AgentSlashDialog?>(null) }
    var slashError by remember(controller.currentSession?.id) { mutableStateOf("") }

    fun runSlashCommand(command: String) {
        when (agentSlashAction(command)) {
            AgentSlashAction.NEW -> controller.newChat()
            AgentSlashAction.MODEL -> slashDialog = AgentSlashDialog.MODEL
            AgentSlashAction.STATUS -> slashDialog = AgentSlashDialog.STATUS
            AgentSlashAction.SETTINGS -> onOpenSettings()
            AgentSlashAction.STOP -> controller.abort()
            AgentSlashAction.HELP -> slashDialog = AgentSlashDialog.HELP
            AgentSlashAction.UNSUPPORTED -> {
                slashError = strings.slashCommandUnsupported(command.substringBefore(' '))
                slashDialog = AgentSlashDialog.ERROR
            }
        }
    }
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
            }
        }
    // 手指松开后 fling 仍属于滚动过程。必须等惯性完全结束且列表确实位于底部，
    // 才恢复自动追尾；否则从底部快速上滑时会在首帧误判并被拉回最新消息。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collectLatest { (isScrollInProgress, canScrollForward) ->
                if (shouldResumeAgentOutputFollow(isScrollInProgress, canScrollForward)) {
                    followOutput = true
                }
            }
    }
    // 消息内容（包括流式 delta）变化也要触发追尾；只监听 turns.size 会漏掉
    // 同一条消息增长，因此生成长回答时滚动位置会停在旧高度。
    LaunchedEffect(turns, followOutput) {
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
                        sessionAgent,
                        controller.agents.firstOrNull { it.id == sessionAgent }?.label ?: strings.assistantLabel,
                        animateAssistant = shouldAnimateAgentTurn(turn.id, controller.activeLocalTurnId),
                        onActivityExpand = { followOutput = false },
                        onOpenArtifact = onOpenArtifact,
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
            controller.currentSession?.agent.orEmpty(),
            voiceSettings,
            onFeedback,
            ::runSlashCommand,
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
    val session = controller.currentSession
    AgentSlashCommandDialog(
        dialog = slashDialog,
        models = modelOptions,
        selectedModel = session?.model.orEmpty(),
        statusText =
            listOf(
                "${strings.defaultAgent}: ${controller.agents.firstOrNull { it.id == session?.agent }?.label ?: session?.agent.orEmpty()}",
                "${strings.defaultModel}: ${session?.model ?: strings.builtInProvider}",
                "${strings.defaultDirectory}: ${session?.cwd.orEmpty()}",
                "${strings.providers}: ${providerConfig?.name ?: strings.builtInProvider}",
            ).joinToString("\n"),
        errorText = slashError,
        onSelectModel = {
            controller.updateSessionModel(it)
            slashDialog = null
        },
        onDismiss = { slashDialog = null },
    )
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
    agentId: String,
    voiceSettings: AppSettings,
    onFeedback: (String) -> Unit,
    onSlashCommand: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val voiceStrings = LocalAppStrings.current.voice
    val scope = rememberCoroutineScope()
    val micPermission = rememberMicPermissionRequester()
    val recorder = remember { MicrophoneRecorder() }
    var voiceEngine by remember { mutableStateOf<AsrEngine?>(null) }
    var voiceState by remember { mutableStateOf(AsrEngine.State.IDLE) }
    var voiceBaseDraft by remember { mutableStateOf("") }
    var voicePartial by remember { mutableStateOf("") }
    val slashCommands = remember(agentId, input) { slashCommandSuggestions(agentId, input) }

    fun resetVoice(abort: Boolean) {
        recorder.stop()
        if (abort) voiceEngine?.abort()
        voiceEngine = null
        voiceState = AsrEngine.State.IDLE
        voiceBaseDraft = ""
        voicePartial = ""
    }

    fun finishVoice() {
        when (voiceState) {
            AsrEngine.State.CONNECTING -> resetVoice(abort = true)
            AsrEngine.State.LISTENING -> {
                recorder.stop()
                voiceEngine?.finish()
            }
            else -> Unit
        }
    }

    fun startVoice() {
        if (voiceState != AsrEngine.State.IDLE) return
        if (!voiceSettings.voiceInputEnabled) {
            onFeedback(voiceStrings.disabled)
            return
        }
        val provider = voiceSettings.asrProviders.firstOrNull { it.enabled }
        if (provider == null) {
            onFeedback(voiceStrings.notConfigured)
            return
        }
        val apiKey = SecretStore.get(SECRET_SERVICE, asrKeyAccount(provider.id))
        if (apiKey.isNullOrBlank()) {
            onFeedback(voiceStrings.notConfigured)
            return
        }
        voiceState = AsrEngine.State.CONNECTING
        voiceBaseDraft = input
        voicePartial = ""
        micPermission.request { granted ->
            scope.launch {
                if (voiceState != AsrEngine.State.CONNECTING) return@launch
                if (!granted) {
                    resetVoice(abort = true)
                    onFeedback(voiceStrings.noPermission)
                    return@launch
                }
                val engine = createAsrEngine(provider, apiKey)
                voiceEngine = engine
                engine.onState = { state ->
                    scope.launch {
                        if (voiceEngine === engine) voiceState = state
                    }
                }
                engine.onPartial = { text ->
                    scope.launch {
                        if (voiceEngine === engine) {
                            voicePartial = text
                            onInput(mergeAgentVoiceDraft(voiceBaseDraft, text))
                        }
                    }
                }
                engine.onFinalText = { text ->
                    scope.launch {
                        if (voiceEngine === engine) {
                            onInput(mergeAgentVoiceDraft(voiceBaseDraft, text))
                            resetVoice(abort = false)
                        }
                    }
                }
                engine.onError = { message ->
                    scope.launch {
                        if (voiceEngine === engine) {
                            resetVoice(abort = false)
                            onFeedback(voiceStrings.error(message))
                        }
                    }
                }
                engine.start()
                val recording =
                    recorder.start(
                        onData = engine::sendPcm,
                        onError = { message ->
                            scope.launch {
                                if (voiceEngine === engine) {
                                    resetVoice(abort = true)
                                    onFeedback(voiceStrings.error(message))
                                }
                            }
                        },
                    )
                if (!recording && voiceEngine === engine) resetVoice(abort = true)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            voiceEngine?.abort()
        }
    }
    LaunchedEffect(busy, enabled) {
        if ((busy || !enabled) && voiceState != AsrEngine.State.IDLE) resetVoice(abort = true)
    }
    LaunchedEffect(voiceEngine) {
        val active = voiceEngine ?: return@LaunchedEffect
        delay(60_000L)
        if (voiceEngine === active && voiceState == AsrEngine.State.LISTENING) {
            onFeedback(voiceStrings.timeout)
            finishVoice()
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Spacing.Xs,
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(Corners.Lg),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.Md).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            if (slashCommands.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(Corners.Md),
                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = Sizes.AgentSlashMenuMaxHeight)) {
                        items(slashCommands, key = { it }) { command ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onInput("")
                                        onSlashCommand(command)
                                    }.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                            ) {
                                Text(command, style = MaterialTheme.typography.labelLarge, fontFamily = monospaceFontFamily())
                                Text(
                                    strings.slashCommand,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
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
            if (voiceState != AsrEngine.State.IDLE) {
                Text(
                    voicePartial.ifBlank {
                        if (voiceState == AsrEngine.State.FINALIZING) voiceStrings.recognizing else voiceStrings.listening
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(min = Sizes.TouchTarget).padding(horizontal = Spacing.Sm, vertical = Spacing.Md)) {
                if (input.isEmpty()) Text(strings.messageHint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    input,
                    onInput,
                    Modifier.fillMaxWidth(),
                    enabled = enabled && !busy && voiceState == AsrEngine.State.IDLE,
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
                if (voiceState == AsrEngine.State.FINALIZING) {
                    CircularProgressIndicator(Modifier.size(Sizes.IconMedium), strokeWidth = Sizes.BorderThin)
                } else {
                    IconButton(
                        onClick = {
                            if (voiceState == AsrEngine.State.IDLE) startVoice() else finishVoice()
                        },
                        enabled = enabled && !busy,
                    ) {
                        Icon(
                            if (voiceState == AsrEngine.State.IDLE) Icons.Default.Mic else Icons.Default.Stop,
                            voiceStrings.menuLabel,
                            tint =
                                if (voiceState == AsrEngine.State.IDLE) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                    }
                }
                if (busy) {
                    FilledTonalIconButton(onStop) { Icon(Icons.Default.Stop, strings.stop) }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (input.trimStart().startsWith("/")) {
                                val command = input.trim()
                                onInput("")
                                onSlashCommand(command)
                            } else {
                                onSend()
                            }
                        },
                        enabled = enabled && (input.isNotBlank() || attachments.isNotEmpty()),
                    ) {
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
private fun AgentTurn(
    turn: AgentTurnUi,
    showThinking: Boolean,
    fallbackAgentId: String,
    fallbackAgentLabel: String,
    animateAssistant: Boolean,
    onActivityExpand: () -> Unit,
    onOpenArtifact: (AgentArtifact) -> Unit,
) {
    val blocks = remember(turn.events, showThinking) { buildAgentTurnBlocks(turn.events, showThinking) }
    val artifacts = remember(turn.events) { collectAgentArtifacts(turn.events) }
    val animatedMessage =
        if (animateAssistant) {
            blocks
                .asReversed()
                .filterIsInstance<AgentMessageBlock>()
                .firstOrNull { it.message.role == "assistant" }
                ?.message
        } else {
            null
        }
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
                                ProcessCards(
                                    groupId = "${turn.id}:$index",
                                    activities = block.activities,
                                    turnRunning = turn.running && index == blocks.lastIndex,
                                    onExpand = onActivityExpand,
                                )
                            is AgentMessageBlock -> {
                                val message = block.message
                                if (message.role == "error") {
                                    ErrorMessage(message)
                                } else {
                                    AssistantMessage(message, message === animatedMessage)
                                }
                            }
                        }
                    }
                    if (artifacts.isNotEmpty()) {
                        AgentArtifactSection(artifacts, onOpenArtifact)
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
    if (animateText) {
        // 流式期间直接由 Compose 状态逐字重组。Markdown 只在回答结束后解析，
        // 避免防抖不断重启把多个字合并成一次闪现。
        Text(
            visibleText,
            modifier = Modifier.fillMaxWidth(),
            style = bodyStyle,
        )
    } else {
        Markdown(
            markdownState = rememberMarkdownState(message.text),
            modifier = Modifier.fillMaxWidth(),
            components = markdownComponents,
            typography = responseTypography,
        )
    }
}

@Composable
private fun AgentArtifactSection(
    artifacts: List<AgentArtifact>,
    onOpen: (AgentArtifact) -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        Text(
            strings.artifactsTitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        artifacts.forEach { artifact ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(artifact) },
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(Corners.Md),
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.padding(Spacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Icon(
                        artifactIcon(artifact.kind),
                        null,
                        Modifier.size(Sizes.IconMedium),
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        Text(
                            artifact.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(artifact.name.substringAfterLast('.', artifact.kind).uppercase())
                                if (artifact.size > 0L) append(" · ${formatFileSize(artifact.size)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        strings.artifactOpenFolder,
                        Modifier.size(Sizes.IconSmall),
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

private fun artifactIcon(kind: String): ImageVector =
    when (kind) {
        "presentation" -> Icons.Default.Slideshow
        "spreadsheet" -> Icons.Default.TableChart
        "pdf" -> Icons.Default.PictureAsPdf
        "image" -> Icons.Default.Image
        "markdown", "document" -> Icons.Default.Description
        "web" -> Icons.Default.Language
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
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
    // rememberSaveable：LazyColumn 滚动把 item 移出组合后回来，动画进度不丢
    // （否则正在流的消息会从空重新打字——「文字半天不出来」的来源之一）
    var visibleText by
        rememberSaveable(key) {
            mutableStateOf(if (animateInitially) "" else message.text)
        }
    LaunchedEffect(key) {
        var lastUpdateAt = 0L
        while (true) {
            val frameAt = withFrameMillis { it }
            val target = latestText.value
            if (!target.startsWith(visibleText)) visibleText = ""
            // 动画结束（turn 完成 / 不再流式）：立即补全全文，不等打字机龟速
            // ——「下面工具都结束了，上面的字还在慢慢出」的修复
            if (!latestAnimate.value) {
                if (visibleText.length < target.length) visibleText = target
                break
            }
            if (visibleText.length < target.length && frameAt - lastUpdateAt >= TYPEWRITER_FRAME_INTERVAL_MILLIS) {
                val remaining = target.length - visibleText.length
                val end = nextTypewriterIndex(target, visibleText.length, typewriterCharsPerFrame(remaining))
                visibleText = target.substring(0, end)
                lastUpdateAt = frameAt
            }
        }
    }
    return visibleText
}

@Composable
private fun ProcessCards(
    groupId: String,
    activities: List<AgentChatMessage>,
    turnRunning: Boolean,
    onExpand: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val failedToolCount = activities.count { it.role == "tool" && it.isError }
    val running = turnRunning || activities.any { it.running }
    var expanded by remember(groupId) { mutableStateOf(false) }
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
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!expanded) onExpand()
                        expanded = !expanded
                    }.padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
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
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    activities.forEach { activity ->
                        ActivityCard(
                            message = activity,
                            animateText = turnRunning,
                            onExpand = onExpand,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    message: AgentChatMessage,
    animateText: Boolean,
    onExpand: () -> Unit,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val hasDetails = message.role != "thinking" || message.text.isNotBlank()
    val toolMeta = agentToolMeta(message.toolName)
    val inputPreview = extractToolSummary(message.toolInput.orEmpty())
    var expanded by remember(message.id, message.activityId) {
        mutableStateOf(false)
    }
    val statusText =
        if (message.running) {
            strings.toolRunning
        } else if (message.isError) {
            strings.toolFailed
        } else {
            strings.toolCompleted
        }
    val duration =
        message.completedAt
            ?.minus(message.createdAt)
            ?.takeIf { message.createdAt > 0L && it >= 0L }
    val status = if (duration != null && !message.running) "$statusText · ${formatAgentDuration(duration)}" else statusText
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
                    .then(
                        if (hasDetails) {
                            Modifier.clickable {
                                if (!expanded) onExpand()
                                expanded = !expanded
                            }
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
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
                            if (message.role == "tool") toolMeta.icon else Icons.Default.SmartToy,
                            null,
                            Modifier.size(Sizes.IconSmall),
                            tint = iconColor,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                    Text(
                        if (message.role == "thinking") strings.thinking else toolMeta.kind.label(strings),
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
                    ToolDetails(message, Modifier.padding(Spacing.Md))
                }
            }
        }
    }
}

/** 工具名 → 图标 + 可本地化语义类型（参考 tuiniverse TOOL_META）。 */
internal enum class AgentToolKind {
    COMMAND,
    FILE_LIST,
    FIND_FILES,
    READ_FILE,
    SEARCH,
    EDIT_FILE,
    CREATE_FILE,
    NETWORK,
    BROWSER,
    TOOL,
}

internal data class AgentToolMeta(
    val icon: ImageVector,
    val kind: AgentToolKind,
)

private val AGENT_TOOL_META: Map<String, AgentToolMeta> =
    linkedMapOf(
        "bash" to AgentToolMeta(Icons.Filled.Terminal, AgentToolKind.COMMAND),
        "shell" to AgentToolMeta(Icons.Filled.Terminal, AgentToolKind.COMMAND),
        "run" to AgentToolMeta(Icons.Filled.Terminal, AgentToolKind.COMMAND),
        "exec" to AgentToolMeta(Icons.Filled.Terminal, AgentToolKind.COMMAND),
        "command" to AgentToolMeta(Icons.Filled.Terminal, AgentToolKind.COMMAND),
        "terminal" to AgentToolMeta(Icons.Filled.Terminal, AgentToolKind.COMMAND),
        "ls" to AgentToolMeta(Icons.Filled.Folder, AgentToolKind.FILE_LIST),
        "list" to AgentToolMeta(Icons.Filled.Folder, AgentToolKind.FILE_LIST),
        "find" to AgentToolMeta(Icons.Filled.Folder, AgentToolKind.FIND_FILES),
        "glob" to AgentToolMeta(Icons.Filled.Folder, AgentToolKind.FIND_FILES),
        "read" to AgentToolMeta(Icons.Filled.Description, AgentToolKind.READ_FILE),
        "view" to AgentToolMeta(Icons.Filled.Description, AgentToolKind.READ_FILE),
        "cat" to AgentToolMeta(Icons.Filled.Description, AgentToolKind.READ_FILE),
        "grep" to AgentToolMeta(Icons.Filled.Search, AgentToolKind.SEARCH),
        "write" to AgentToolMeta(Icons.Filled.Edit, AgentToolKind.EDIT_FILE),
        "edit" to AgentToolMeta(Icons.Filled.Edit, AgentToolKind.EDIT_FILE),
        "create" to AgentToolMeta(Icons.Filled.Edit, AgentToolKind.CREATE_FILE),
        "web" to AgentToolMeta(Icons.Filled.Public, AgentToolKind.NETWORK),
        "fetch" to AgentToolMeta(Icons.Filled.Public, AgentToolKind.NETWORK),
        "http" to AgentToolMeta(Icons.Filled.Public, AgentToolKind.NETWORK),
        "browser" to AgentToolMeta(Icons.Filled.Language, AgentToolKind.BROWSER),
        "tool" to AgentToolMeta(Icons.Filled.Bolt, AgentToolKind.TOOL),
    )

private fun AgentToolKind.label(strings: NativeAgentStrings): String =
    when (this) {
        AgentToolKind.COMMAND -> strings.toolCommand
        AgentToolKind.FILE_LIST -> strings.toolFileList
        AgentToolKind.FIND_FILES -> strings.toolFindFiles
        AgentToolKind.READ_FILE -> strings.toolReadFile
        AgentToolKind.SEARCH -> strings.toolSearch
        AgentToolKind.EDIT_FILE -> strings.toolEditFile
        AgentToolKind.CREATE_FILE -> strings.toolCreateFile
        AgentToolKind.NETWORK -> strings.toolNetwork
        AgentToolKind.BROWSER -> strings.toolBrowser
        AgentToolKind.TOOL -> strings.tool
    }

internal fun agentToolMeta(name: String?): AgentToolMeta {
    val key = (name ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")
    if (key.isBlank()) return AGENT_TOOL_META.getValue("tool")
    for ((k, meta) in AGENT_TOOL_META) {
        if (key == k || key.startsWith(k)) return meta
    }
    return AGENT_TOOL_META.getValue("tool")
}

/** 从工具参数 JSON 提取命令（bash 等执行类工具）。 */
internal fun extractToolCommand(input: String): String? {
    if (input.isBlank()) return null
    val obj =
        try {
            Json.parseToJsonElement(input).jsonObject
        } catch (_: Exception) {
            return null
        }
    val cmd =
        obj["command"]?.jsonPrimitive?.contentOrNull
            ?: obj["cmd"]?.jsonPrimitive?.contentOrNull
    return cmd?.trim()?.takeIf { it.isNotBlank() }
}

/** 工具行单行摘要：command / path / url / 参数前 40 字（参考 tuiniverse extractSummary）。 */
internal fun extractToolSummary(input: String): String {
    if (input.isBlank()) return ""
    val obj =
        try {
            Json.parseToJsonElement(input).jsonObject
        } catch (_: Exception) {
            return input
                .trim()
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .take(40)
        }
    val keys = listOf("command", "cmd", "path", "file", "file_path", "glob", "pattern", "query", "url")
    for (k in keys) {
        val v = obj[k]?.jsonPrimitive?.contentOrNull?.trim()
        if (!v.isNullOrBlank()) return v.take(60)
    }
    return input
        .trim()
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .take(40)
}

/** 展开区输入：有 command 时去掉 command 字段，展示其余参数（避免重复）。 */
internal fun removeCommandFromInput(input: String): String {
    if (input.isBlank()) return ""
    val obj =
        try {
            Json.parseToJsonElement(input).jsonObject
        } catch (_: Exception) {
            return input
        }
    val filtered = obj.filterKeys { it != "command" && it != "cmd" }
    if (filtered.isEmpty()) return ""
    return filtered.entries.joinToString("\n") { (k, v) -> "$k: $v" }
}

internal fun formatAgentDuration(ms: Long): String =
    when {
        ms < 1_000 -> "${ms}ms"
        (ms + 500) / 1_000 < 60 -> "${(ms + 500) / 1_000}s"
        else -> {
            val seconds = (ms + 500) / 1_000
            "${seconds / 60}m ${seconds % 60}s"
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
private fun ToolDetails(
    message: AgentChatMessage,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current.nativeAgents
    val rawInput = message.toolInput.orEmpty().trim()
    val input = if (extractToolCommand(rawInput) != null) removeCommandFromInput(rawInput) else rawInput
    val output = message.text.trim()
    if (input.isBlank() && output.isBlank()) return
    Column(
        modifier.fillMaxWidth(),
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(Corners.Sm),
        ) {
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
        // 短回答严格逐字显示；只在模型一次涌入大段文本时加速追平积压，
        // 防止工具已结束而正文仍长时间未显示完。
        remaining > 2_000 -> 48
        remaining > 800 -> 24
        remaining > 300 -> 12
        remaining > 80 -> 4
        else -> 1
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

// ===================== 终端「+」工具：Agent 页实现 =====================

/** Agent 页上传状态（驱动右上角上传进度浮层）。 */
private sealed interface AgentUploadUiState {
    data object Idle : AgentUploadUiState

    /** 上传中：当前文件进度。 */
    data class Uploading(
        val name: String,
        val sent: Long,
        val total: Long,
        val doneCount: Int,
        val totalCount: Int,
    ) : AgentUploadUiState

    /** 全部完成（paths = 成功上传的远端路径）。 */
    data class Done(
        val count: Int,
        val paths: List<String>,
    ) : AgentUploadUiState

    /** 失败（含原因，UI 提示后回 Idle）。 */
    data class Failed(
        val message: String,
    ) : AgentUploadUiState
}

/**
 * Agent 页文件上传器：复用 Agent Bridge 的凭据/回调新建 SFTP 连接（与终端页
 * [TerminalFileUploader] 同模式），多文件串行流式上传，任意大小不整体驻内存。
 */
private class AgentUploader(
    private val controller: AgentBridgeController,
    private val scope: CoroutineScope,
) {
    var state: AgentUploadUiState by mutableStateOf(AgentUploadUiState.Idle)
        private set

    private val queue = ArrayDeque<PickedFile>()
    private var job: Job? = null

    /** 入队并启动（若未在传）串行上传。 */
    fun enqueue(
        file: PickedFile,
        targetDir: String,
    ) {
        queue.addLast(file)
        if (job?.isActive != true) {
            job = scope.launch { drain(targetDir) }
        }
    }

    /** 上传完成/失败提示后回 Idle。 */
    fun clear() {
        state = AgentUploadUiState.Idle
    }

    private suspend fun drain(targetDir: String) {
        val total = queue.size
        var done = 0
        val uploadedPaths = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            try {
                val path = uploadOne(f, targetDir, done, total)
                uploadedPaths.add(path)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = AgentUploadUiState.Failed(e.message ?: (e::class.simpleName ?: "error"))
                return
            }
        }
        state = AgentUploadUiState.Done(done, uploadedPaths)
    }

    private suspend fun uploadOne(
        picked: PickedFile,
        targetDir: String,
        index: Int,
        queueTotal: Int,
    ): String {
        val sftp = controller.openSftp()
        try {
            val remotePath =
                if (targetDir.endsWith("/")) "$targetDir${picked.name}" else "$targetDir/${picked.name}"
            withContext(ioDispatcher()) {
                sftp.upload(
                    remotePath = remotePath,
                    totalSize = picked.size,
                    onProgress = { sent, _ ->
                        state = AgentUploadUiState.Uploading(picked.name, sent, picked.size, index, queueTotal)
                    },
                    nextChunk = { picked.readChunk() },
                )
            }
            return remotePath
        } finally {
            withContext(ioDispatcher()) { runCatching { sftp.close() } }
        }
    }
}

/**
 * Agent 页文件管理/收藏夹：直接复用终端页完整 SFTP（SftpContent）——
 * 面包屑/文件类型图标/日期分组/多选/预览/收藏面板/上传/搜索/排序/下拉刷新
 * 全部与终端页一致。独立 SFTP 会话（Agent Bridge 连接不受影响），关闭弹窗释放。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentSftpOverlay(
    controller: AgentBridgeController,
    repository: HostRepository,
    initialPath: String?,
    ownerScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<SftpSession?>(null) }
    val uiState = remember { SftpUiState() }
    var connectError by remember { mutableStateOf<String?>(null) }
    val latestSession = rememberUpdatedState(session)

    // 建立 SFTP 会话（复用 Agent Bridge 凭据/回调弹窗），恢复收藏 + 初始路径
    LaunchedEffect(Unit) {
        repository.loadFavorites(controller.host.id).forEach { uiState.favorites.add(it) }
        if (!initialPath.isNullOrBlank()) uiState.path = initialPath
        try {
            val sc = controller.openSftp()
            session = sc
        } catch (e: Exception) {
            connectError = e.message
        }
    }

    // 弹窗关闭释放会话（Agent 对话连接不受影响）
    DisposableEffect(Unit) {
        onDispose {
            val sc = latestSession.value
            if (sc != null) {
                ownerScope.launch { withContext(ioDispatcher()) { runCatching { sc.close() } } }
            }
        }
    }

    // 断线重连：重建会话替换（SftpContent 的 banner 按钮触发）
    fun reconnect() {
        scope.launch {
            uiState.reconnecting = true
            connectError = null
            try {
                val sc = controller.openSftp()
                session?.let { old -> withContext(ioDispatcher()) { runCatching { old.close() } } }
                session = sc
                uiState.reconnecting = false
                uiState.disconnected = false
            } catch (e: Exception) {
                uiState.reconnecting = false
                if (session == null) {
                    connectError = e.message
                } else {
                    uiState.loadError = e.message
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        LocalAppStrings.current.settingsClose,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    LocalAppStrings.current.upload.fileManagerLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (session != null) {
                    SftpContent(
                        host = controller.host,
                        session = session,
                        state = uiState,
                        onBack = onDismiss,
                        onReconnect = ::reconnect,
                        onFavoritesChanged = { repository.saveFavorites(controller.host.id, it) },
                        // Agent 页浏览路径不持久化（与终端页 SFTP 会话独立）
                        onPathChanged = {},
                    )
                } else if (connectError != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        ) {
                            Text(
                                connectError.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            FilledTonalButton(onClick = ::reconnect) {
                                Text(LocalAppStrings.current.sftpReconnect)
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(Sizes.IconMedium))
                    }
                }
            }
        }
    }
}
