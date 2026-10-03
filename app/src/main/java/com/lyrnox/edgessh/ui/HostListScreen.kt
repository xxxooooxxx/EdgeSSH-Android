package com.lyrnox.edgessh.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lyrnox.edgessh.data.CredentialStore
import com.lyrnox.edgessh.data.Host
import com.lyrnox.edgessh.data.HostStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主机列表 ViewModel。 */
class HostListViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx = app.applicationContext

    var hosts by mutableStateOf(emptyList<Host>())
        private set
    var query by mutableStateOf("")
        private set

    /** 待确认删除的主机（非空时弹确认框）。 */
    var pendingDelete by mutableStateOf<Host?>(null)
        private set

    val filtered: List<Host>
        get() = if (query.isBlank()) hosts else hosts.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.hostname.contains(query, ignoreCase = true) ||
                it.username.contains(query, ignoreCase = true)
        }

    init {
        reload()
    }

    fun setQuery(q: String) {
        query = q
    }

    /** 从存储重读（从编辑页返回时也会调）。 */
    fun reload() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { HostStore(appCtx).list() }
            hosts = list.sortedBy { it.name.lowercase() }
        }
    }

    fun confirmDelete(host: Host) {
        pendingDelete = host
    }

    fun cancelDelete() {
        pendingDelete = null
    }

    fun doDelete() {
        val h = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HostStore(appCtx).delete(h.id)
                // 主机删掉，存的密码/私钥一并清除
                CredentialStore(appCtx).clear(h.id)
            }
            reload()
        }
    }
}

/** 主机列表页：搜索、连接、编辑、删除，底部进代码片段。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(nav: NavController) {
    val vm: HostListViewModel = viewModel()

    // 从编辑页返回（ON_RESUME）时刷新列表
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reload()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("EdgeSSH") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.hostEdit()) }) {
                Icon(Icons.Filled.Add, contentDescription = "新增主机")
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Computer, contentDescription = null) },
                    label = { Text("主机") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { nav.navigate(Routes.SNIPPETS) },
                    icon = { Icon(Icons.Filled.Code, contentDescription = null) },
                    label = { Text("代码片段") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::setQuery,
                label = { Text("搜索主机") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.padding(top = 8.dp))
            val list = vm.filtered
            if (list.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (vm.hosts.isEmpty()) "还没有主机，点右下角 + 添加" else "没有匹配的主机",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { host ->
                        HostRow(
                            host = host,
                            onConnect = { nav.navigate(Routes.terminal(host.id)) },
                            onEdit = { nav.navigate(Routes.hostEdit(host.id)) },
                            onDelete = { vm.confirmDelete(host) },
                        )
                    }
                }
            }
        }
    }

    vm.pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("删除主机") },
            text = { Text("确定删除「${host.name}」吗？保存的密码 / 私钥也会一并清除。") },
            confirmButton = {
                TextButton(onClick = vm::doDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDelete) { Text("取消") }
            },
        )
    }
}

/** 单台主机的卡片行：点卡片连接，右侧编辑/删除。 */
@Composable
private fun HostRow(
    host: Host,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(host.name) },
            supportingContent = { Text("${host.username}@${host.hostname}:${host.port}") },
            leadingContent = {
                Icon(Icons.Filled.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Row {
                    if (host.group.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(host.group) })
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
            modifier = Modifier.clickable(onClick = onConnect),
        )
    }
}
