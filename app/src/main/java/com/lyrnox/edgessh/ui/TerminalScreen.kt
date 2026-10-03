package com.lyrnox.edgessh.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lyrnox.edgessh.data.FingerprintStore
import com.lyrnox.edgessh.data.HostStore
import com.lyrnox.edgessh.data.Snippet
import com.lyrnox.edgessh.data.SnippetStore
import com.lyrnox.edgessh.ssh.FingerprintUnknownException
import com.lyrnox.edgessh.ssh.SshConnection
import com.lyrnox.edgessh.terminal.SshTerminalController
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TerminalScreen"
private const val CONNECT_TIMEOUT_MS = 15_000

/** 终端页 UI 状态。 */
sealed interface TerminalUiState {
    /** 正在连接。 */
    data object Connecting : TerminalUiState

    /** 遇到未知主机密钥，等用户确认。 */
    data class FingerprintPrompt(
        val hostname: String,
        val port: Int,
        val sha256: String,
        val algorithm: String,
    ) : TerminalUiState

    /** 已连接，title 为主机名（可能被远端 OSC 标题覆盖）。 */
    data class Connected(val title: String) : TerminalUiState

    /** 连接失败。 */
    data class Error(val message: String) : TerminalUiState

    /** 已断开（远端断开 / 用户主动断开）。 */
    data object Disconnected : TerminalUiState
}

/** SSH 终端 ViewModel：负责连接、指纹确认、controller 生命周期。 */
class TerminalViewModel(app: Application, private val hostId: String) : AndroidViewModel(app) {
    private val appCtx = app.applicationContext
    private val fingerprints = FingerprintStore(appCtx)

    var uiState by mutableStateOf<TerminalUiState>(TerminalUiState.Connecting)
        private set

    /** 连接成功后在主线程创建，AndroidView 里 attach 到 TerminalView。 */
    var controller by mutableStateOf<SshTerminalController?>(null)
        private set

    /** 由 AndroidView factory 赋值，供 sessionClient 回调用。 */
    var terminalView: TerminalView? = null

    var snippets by mutableStateOf(emptyList<Snippet>())
        private set

    private var conn: SshConnection? = null

    /** Termux 会话回调：方法名必须与 TerminalSessionClient 精确一致。 */
    val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            // 远端有输出，通知 view 重绘
            terminalView?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            val t = changedSession.title
            if (!t.isNullOrBlank()) {
                val cur = uiState
                if (cur is TerminalUiState.Connected) uiState = cur.copy(title = t)
            }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            uiState = TerminalUiState.Disconnected
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(appCtx)?.toString()
            if (!text.isNullOrEmpty()) session.write(text)
        }

        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}

        /** 返回 null 表示用默认块状光标（库内会回退到 DEFAULT_TERMINAL_CURSOR_STYLE）。 */
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) = Log.e(tag, message)
        override fun logWarn(tag: String, message: String) = Log.w(tag, message)
        override fun logInfo(tag: String, message: String) = Log.i(tag, message)
        override fun logDebug(tag: String, message: String) = Log.d(tag, message)
        override fun logVerbose(tag: String, message: String) = Log.v(tag, message)
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
            Log.e(tag, message, e)

        override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "stacktrace", e)
    }

    /** Termux 视图回调：方法名必须与 TerminalViewClient 精确一致（v0.118.3）。 */
    val viewClient = object : TerminalViewClient {
        /** 双指缩放：把缩放系数钳在合理范围后返回（view 本身不用它做渲染）。 */
        override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 3f)
        override fun onSingleTapUp(e: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, currentSession: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        /** 返回 false 走 Termux 默认输入处理。 */
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {}

        override fun logError(tag: String, message: String) = Log.e(tag, message)
        override fun logWarn(tag: String, message: String) = Log.w(tag, message)
        override fun logInfo(tag: String, message: String) = Log.i(tag, message)
        override fun logDebug(tag: String, message: String) = Log.d(tag, message)
        override fun logVerbose(tag: String, message: String) = Log.v(tag, message)
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
            Log.e(tag, message, e)

        override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "stacktrace", e)
    }

    init {
        connect()
        loadSnippets()
    }

    /** 建立 SSH 连接；指纹未知时转 FingerprintPrompt 状态等用户确认。 */
    fun connect() {
        // 旧连接先清掉再重连
        runCatching { controller?.close() }
        controller = null
        uiState = TerminalUiState.Connecting
        viewModelScope.launch {
            try {
                val host = withContext(Dispatchers.IO) { HostStore(appCtx).get(hostId) }
                    ?: throw IllegalStateException("找不到该主机，可能已被删除")
                val creds = withContext(Dispatchers.IO) { buildSshCredentials(appCtx, host) }
                val c = SshConnection(appCtx, fingerprints)
                withContext(Dispatchers.IO) { c.connect(host, creds, CONNECT_TIMEOUT_MS) }
                conn = c
                // 必须在主线程创建：内部 Handler 绑定创建线程的 Looper
                val ctl = SshTerminalController(c, sessionClient)
                ctl.onDisconnected = {
                    // 在 IO 线程回调，切回主线程更新状态
                    viewModelScope.launch { uiState = TerminalUiState.Disconnected }
                }
                controller = ctl
                uiState = TerminalUiState.Connected(host.name)
                withContext(Dispatchers.IO) { ctl.start(80, 24) }
            } catch (e: FingerprintUnknownException) {
                uiState = TerminalUiState.FingerprintPrompt(
                    hostname = e.hostname,
                    port = e.port,
                    sha256 = e.sha256Fingerprint,
                    algorithm = e.keyAlgorithm,
                )
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                uiState = TerminalUiState.Error(e.message ?: e.toString())
            }
        }
    }

    /** 用户确认信任该主机密钥：记入 FingerprintStore 后重试连接。 */
    fun acceptFingerprint() {
        val p = uiState as? TerminalUiState.FingerprintPrompt ?: return
        fingerprints.put(p.hostname, p.port, p.sha256)
        connect()
    }

    /** 用户主动断开。 */
    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller?.close() }
        }
        uiState = TerminalUiState.Disconnected
    }

    fun loadSnippets() {
        viewModelScope.launch {
            snippets = withContext(Dispatchers.IO) { SnippetStore(appCtx).list() }
        }
    }

    /** 把片段命令填入终端（不执行）。 */
    fun fillSnippet(snippet: Snippet) {
        controller?.termSession?.write(snippet.command)
    }

    /** 把片段命令填入并回车执行。 */
    fun runSnippet(snippet: Snippet) {
        controller?.termSession?.write(snippet.command + "\r")
    }

    /** 界面销毁时释放连接。 */
    fun dispose() {
        runCatching { controller?.close() }
        controller = null
        conn = null
        // 旋转屏幕等重建时 controller 已关，用"已断开"态让用户点重连
        if (uiState is TerminalUiState.Connected) {
            uiState = TerminalUiState.Disconnected
        }
    }

    override fun onCleared() {
        runCatching { controller?.close() }
        super.onCleared()
    }
}

/** SSH 终端页。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(nav: NavController, hostId: String) {
    val app = LocalContext.current.applicationContext as Application
    val vm: TerminalViewModel = viewModel(
        key = "terminal-$hostId",
        factory = HostScopedFactory(app, hostId, ::TerminalViewModel),
    )
    val state = vm.uiState
    val controller = vm.controller
    var showSnippets by remember { mutableStateOf(false) }

    // 离开页面时关闭连接、释放资源
    DisposableEffect(Unit) {
        onDispose { vm.dispose() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text((state as? TerminalUiState.Connected)?.title ?: "终端")
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 文件管理（与终端各连一次，简单可靠）
                    IconButton(onClick = { nav.navigate(Routes.files(hostId)) }) {
                        Icon(Icons.Filled.Folder, contentDescription = "文件管理")
                    }
                    IconButton(onClick = { vm.loadSnippets(); showSnippets = true }) {
                        Icon(Icons.Filled.Code, contentDescription = "代码片段")
                    }
                    IconButton(onClick = { vm.disconnect() }) {
                        Icon(Icons.Filled.Close, contentDescription = "断开连接")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(), // 软键盘弹起时不遮住终端
        ) {
            when (state) {
                is TerminalUiState.Connecting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在连接…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                is TerminalUiState.FingerprintPrompt -> {
                    // 底层仍是连接中，弹窗让用户确认指纹
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    AlertDialog(
                        onDismissRequest = { nav.popBackStack() },
                        title = { Text("未知的主机密钥") },
                        text = {
                            Column {
                                Text("首次连接 ${state.hostname}:${state.port}，请核对服务器密钥指纹后再继续：")
                                Spacer(Modifier.height(8.dp))
                                Text("算法：${state.algorithm}")
                                Spacer(Modifier.height(4.dp))
                                Text("SHA256 指纹：")
                                SelectionContainer {
                                    Text(
                                        state.sha256,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { vm.acceptFingerprint() }) {
                                Text("信任并继续")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { nav.popBackStack() }) {
                                Text("取消")
                            }
                        },
                    )
                }

                is TerminalUiState.Error -> {
                    CenterMessage(
                        title = "连接失败",
                        message = state.message,
                        primaryLabel = "重试",
                        onPrimary = { vm.connect() },
                        secondaryLabel = "返回",
                        onSecondary = { nav.popBackStack() },
                    )
                }

                is TerminalUiState.Disconnected -> {
                    CenterMessage(
                        title = "连接已断开",
                        message = null,
                        primaryLabel = "重新连接",
                        onPrimary = { vm.connect() },
                        secondaryLabel = "返回",
                        onSecondary = { nav.popBackStack() },
                    )
                }

                is TerminalUiState.Connected -> {
                    val ctl = controller
                    if (ctl == null) {
                        // controller 已被释放（如旋转屏幕），让用户手动重连
                        CenterMessage(
                            title = "连接已断开",
                            message = null,
                            primaryLabel = "重新连接",
                            onPrimary = { vm.connect() },
                            secondaryLabel = "返回",
                            onSecondary = { nav.popBackStack() },
                        )
                    } else {
                        // ctl 变化（重连）时重建整个 view，保证新 session 被 attach
                        key(ctl) {
                            AndroidView(
                                factory = { ctx ->
                                    // v0.118.3 的 TerminalView 只有 (Context, AttributeSet) 构造器
                                    TerminalView(ctx, null).apply {
                                        setTextSize(38)
                                        setTerminalViewClient(vm.viewClient)
                                        attachSession(ctl.termSession)
                                        // 让 view 能拿到焦点以弹出软键盘
                                        isFocusableInTouchMode = true
                                        post { requestFocus() }
                                        vm.terminalView = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            // 每 500ms 检查终端尺寸，变化就通知远端（window-change）
                            LaunchedEffect(ctl) {
                                var lastCols = 80
                                var lastRows = 24
                                while (true) {
                                    delay(500)
                                    val cols = ctl.currentCols()
                                    val rows = ctl.currentRows()
                                    if (cols != lastCols || rows != lastRows) {
                                        lastCols = cols
                                        lastRows = rows
                                        withContext(Dispatchers.IO) {
                                            ctl.sendWindowChange(cols, rows)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSnippets) {
        SnippetPickerDialog(
            snippets = vm.snippets,
            onFill = { vm.fillSnippet(it); showSnippets = false },
            onRun = { vm.runSnippet(it); showSnippets = false },
            onDismiss = { showSnippets = false },
        )
    }
}

/** 居中提示（错误 / 断开时用）。 */
@Composable
private fun CenterMessage(
    title: String,
    message: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(primaryLabel)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                Text(secondaryLabel)
            }
        }
    }
}

/** 代码片段选择框：点条目填入，长按或点 ▶ 直接执行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetPickerDialog(
    snippets: List<Snippet>,
    onFill: (Snippet) -> Unit,
    onRun: (Snippet) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("代码片段") },
        text = {
            if (snippets.isEmpty()) {
                Text("还没有代码片段，先去「代码片段」页添加吧。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(snippets, key = { it.id }) { s ->
                        ListItem(
                            headlineContent = { Text(s.title) },
                            supportingContent = {
                                Text(s.command, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            },
                            trailingContent = {
                                IconButton(onClick = { onRun(s) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "执行")
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = { onFill(s) },
                                onLongClick = { onRun(s) },
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
