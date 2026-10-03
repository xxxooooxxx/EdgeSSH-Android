package com.lyrnox.edgessh.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectionContainer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lyrnox.edgessh.data.FingerprintStore
import com.lyrnox.edgessh.data.HostStore
import com.lyrnox.edgessh.ssh.FingerprintUnknownException
import com.lyrnox.edgessh.ssh.SftpManager
import com.lyrnox.edgessh.ssh.SshConnection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FileManager"
private const val CONNECT_TIMEOUT_MS = 15_000

/** 远端路径拼接。 */
private fun joinRemote(parent: String, name: String): String =
    if (parent == "/") "/$name" else "$parent/$name"

/** 取父目录。 */
private fun parentRemote(path: String): String {
    if (path == "/") return "/"
    return path.trimEnd('/').substringBeforeLast("/", "").ifEmpty { "/" }
}

/** 人性化文件大小。 */
private fun formatSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** 读 SAF 选中文件的文件名。 */
private fun Context.displayName(uri: Uri): String? {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}

/**
 * 用外部应用打开下载好的文件；没有可打开的应用时返回 false。
 * 依赖 manifest 里已配好的 FileProvider（authorities = "${applicationId}.fileprovider"）。
 */
private fun openWithExternalApp(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "打开文件"))
        true
    } catch (e: Exception) {
        Log.w(TAG, "open file failed", e)
        false
    }
}

/** 未确认的指纹信息。 */
private data class FpPrompt(
    val hostname: String,
    val port: Int,
    val sha256: String,
    val algorithm: String,
)

/**
 * 文件管理 ViewModel：自己再建一条 SshConnection 走 SFTP（与终端互不干扰）。
 *
 * 依赖 ssh 包的 SftpManager（其 Entry 为 SftpManager 的嵌套类）：
 * - SftpManager(conn)
 * - listDir(path): List<SftpManager.Entry>（已按目录优先、名称排序）
 * - download(remotePath, localFile) / uploadStream(inputStream, remotePath)
 * - mkdir(path) / rename(oldPath, newPath) / delete(entry)（目录递归删除）
 * - SftpManager.Entry(name, path, isDir, size, lastModified)
 * - close()
 */
class FileManagerViewModel(app: Application, private val hostId: String) : AndroidViewModel(app) {
    private val appCtx = app.applicationContext
    private val fingerprints = FingerprintStore(appCtx)

    var connecting by mutableStateOf(true)
        private set
    var fpPrompt by mutableStateOf<FpPrompt?>(null)
        private set
    var fatalError by mutableStateOf<String?>(null)
        private set

    var path by mutableStateOf("/")
        private set
    var entries by mutableStateOf(emptyList<SftpManager.Entry>())
        private set
    var loading by mutableStateOf(false)
        private set
    var errorMsg by mutableStateOf<String?>(null)

    private var conn: SshConnection? = null
    private var sftpMgr: SftpManager? = null

    init {
        connect()
    }

    fun connect() {
        viewModelScope.launch {
            connecting = true
            fatalError = null
            fpPrompt = null
            try {
                val host = withContext(Dispatchers.IO) { HostStore(appCtx).get(hostId) }
                    ?: throw IllegalStateException("找不到该主机")
                val creds = withContext(Dispatchers.IO) { buildSshCredentials(appCtx, host) }
                val c = SshConnection(appCtx, fingerprints)
                withContext(Dispatchers.IO) { c.connect(host, creds, CONNECT_TIMEOUT_MS) }
                conn = c
                sftpMgr = SftpManager(c)
                refresh()
            } catch (e: FingerprintUnknownException) {
                fpPrompt = FpPrompt(e.hostname, e.port, e.sha256Fingerprint, e.keyAlgorithm)
            } catch (e: Exception) {
                Log.w(TAG, "sftp connect failed", e)
                fatalError = e.message ?: e.toString()
            } finally {
                connecting = false
            }
        }
    }

    fun acceptFingerprint() {
        val p = fpPrompt ?: return
        fingerprints.put(p.hostname, p.port, p.sha256)
        fpPrompt = null
        connect()
    }

    /** 刷新当前目录。 */
    fun refresh() {
        val mgr = sftpMgr ?: return
        viewModelScope.launch {
            loading = true
            errorMsg = null
            try {
                // listDir 已按目录优先、名称排序，直接用
                entries = withContext(Dispatchers.IO) { mgr.listDir(path) }
            } catch (e: Exception) {
                errorMsg = "读取目录失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    /** 写操作（新建/删除/重命名/上传）走这里，成功后自动刷新。 */
    private fun mutate(errorPrefix: String, block: suspend (SftpManager) -> Unit) {
        val mgr = sftpMgr ?: return
        viewModelScope.launch {
            loading = true
            errorMsg = null
            try {
                withContext(Dispatchers.IO) { block(mgr) }
            } catch (e: Exception) {
                errorMsg = "$errorPrefix：${e.message}"
                loading = false
                return@launch
            }
            refresh()
        }
    }

    fun enter(dir: SftpManager.Entry) {
        path = dir.path
        refresh()
    }

    fun goUp() {
        val parent = parentRemote(path)
        if (parent != path) {
            path = parent
            refresh()
        }
    }

    fun mkdir(name: String) = mutate("新建文件夹失败") { it.mkdir(joinRemote(path, name)) }

    fun rename(entry: SftpManager.Entry, newName: String) = mutate("重命名失败") {
        it.rename(entry.path, joinRemote(parentRemote(entry.path), newName))
    }

    fun delete(entry: SftpManager.Entry) = mutate("删除失败") { it.delete(entry) }

    /** 下载到 getExternalFilesDir("downloads")，完成后回调本地文件。 */
    fun download(entry: SftpManager.Entry, onDone: (File) -> Unit) {
        val mgr = sftpMgr ?: return
        viewModelScope.launch {
            loading = true
            errorMsg = null
            try {
                val dir = File(appCtx.getExternalFilesDir("downloads"), "").apply { mkdirs() }
                val local = File(dir, entry.name)
                withContext(Dispatchers.IO) { mgr.download(entry.path, local) }
                onDone(local)
            } catch (e: Exception) {
                errorMsg = "下载失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    /** 上传 SAF 选中的文件到当前目录。 */
    fun upload(uri: Uri) {
        mutate("上传失败") { mgr ->
            val name = appCtx.displayName(uri) ?: "upload_${System.currentTimeMillis()}"
            val remote = joinRemote(path, name)
            val input = appCtx.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("打不开选中的文件")
            input.use { mgr.uploadStream(it, remote) }
        }
    }

    fun clearError() {
        errorMsg = null
    }

    fun dispose() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sftpMgr?.close() }
            runCatching { conn?.disconnect() }
        }
        conn = null
        sftpMgr = null
    }

    override fun onCleared() {
        runCatching { sftpMgr?.close() }
        runCatching { conn?.disconnect() }
        super.onCleared()
    }
}

/** 文件管理页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(nav: NavController, hostId: String) {
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    val vm: FileManagerViewModel = viewModel(
        key = "files-$hostId",
        factory = HostScopedFactory(app, hostId, ::FileManagerViewModel),
    )
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var menuExpanded by remember { mutableStateOf(false) }
    var fileOpsTarget by remember { mutableStateOf<SftpManager.Entry?>(null) }
    var mkdirDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SftpManager.Entry?>(null) }
    var deleteTarget by remember { mutableStateOf<SftpManager.Entry?>(null) }

    DisposableEffect(Unit) {
        onDispose { vm.dispose() }
    }

    // SAF 文件选择：上传用
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(uri)
    }

    fun showSnackbar(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        vm.path,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回终端")
                    }
                },
                actions = {
                    // 上级目录
                    if (vm.path != "/") {
                        IconButton(onClick = vm::goUp) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "上级目录")
                        }
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    // 新建文件夹 / 上传
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建 / 上传")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建文件夹") },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                                onClick = { menuExpanded = false; mkdirDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("上传文件") },
                                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    pickFile.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                vm.connecting -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在连接 SFTP…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                vm.fatalError != null -> {
                    FileCenterMessage(
                        title = "连接失败",
                        message = vm.fatalError,
                        onRetry = vm::connect,
                        onBack = { nav.popBackStack() },
                    )
                }

                else -> {
                    if (vm.entries.isEmpty() && !vm.loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(vm.entries, key = { it.path }) { entry ->
                                ListItem(
                                    headlineContent = { Text(entry.name) },
                                    supportingContent = {
                                        if (!entry.isDir) {
                                            Text(formatSize(entry.size), style = MaterialTheme.typography.bodySmall)
                                        }
                                    },
                                    leadingContent = {
                                        Icon(
                                            if (entry.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        if (entry.isDir) vm.enter(entry)
                                        else fileOpsTarget = entry
                                    },
                                )
                            }
                        }
                    }
                    if (vm.loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            vm.errorMsg?.let { msg ->
                // 出错时弹 snackbar，展示后清掉避免重复
                LaunchedEffect(msg) {
                    snackbar.showSnackbar(msg)
                    vm.clearError()
                }
            }
        }
    }

    // 指纹确认弹窗
    vm.fpPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { nav.popBackStack() },
            title = { Text("未知的主机密钥") },
            text = {
                Column {
                    Text("首次连接 ${p.hostname}:${p.port}，请核对服务器密钥指纹：")
                    Spacer(Modifier.height(8.dp))
                    Text("算法：${p.algorithm}")
                    Spacer(Modifier.height(4.dp))
                    Text("SHA256 指纹：")
                    SelectionContainer {
                        Text(p.sha256, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::acceptFingerprint) { Text("信任并继续") } },
            dismissButton = { TextButton(onClick = { nav.popBackStack() }) { Text("取消") } },
        )
    }

    // 点文件：下载 / 重命名 / 删除
    fileOpsTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { fileOpsTarget = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            fileOpsTarget = null
                            vm.download(entry) { file ->
                                if (!openWithExternalApp(context, file)) {
                                    showSnackbar("已保存到 ${file.absolutePath}，没有可打开此文件的应用")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("下载并打开")
                    }
                    TextButton(
                        onClick = { fileOpsTarget = null; renameTarget = entry },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("重命名")
                    }
                    TextButton(
                        onClick = { fileOpsTarget = null; deleteTarget = entry },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { fileOpsTarget = null }) { Text("取消") } },
        )
    }

    if (mkdirDialog) {
        NameInputDialog(
            title = "新建文件夹",
            initial = "",
            confirmLabel = "创建",
            onConfirm = { mkdirDialog = false; vm.mkdir(it) },
            onDismiss = { mkdirDialog = false },
        )
    }

    renameTarget?.let { entry ->
        NameInputDialog(
            title = "重命名",
            initial = entry.name,
            confirmLabel = "确定",
            onConfirm = { renameTarget = null; vm.rename(entry, it) },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除") },
            text = { Text("确定删除「${entry.name}」吗？${if (entry.isDir) "目录会连同里面的内容一起删除。" else ""}") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; vm.delete(entry) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 错误 / 空状态的居中提示。 */
@Composable
private fun FileCenterMessage(
    title: String,
    message: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
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
            TextButton(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}

/** 通用名称输入框（新建文件夹 / 重命名）。 */
@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isBlank() || text.contains('/')) {
                    error = "名称不能为空，也不能包含 /"
                } else {
                    onConfirm(text.trim())
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
