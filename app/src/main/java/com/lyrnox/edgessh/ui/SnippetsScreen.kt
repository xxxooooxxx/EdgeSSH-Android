package com.lyrnox.edgessh.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lyrnox.edgessh.data.Snippet
import com.lyrnox.edgessh.data.SnippetStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 代码片段 ViewModel。 */
class SnippetsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SnippetStore(app.applicationContext)

    var snippets by mutableStateOf(emptyList<Snippet>())
        private set

    /** 非空时弹出新增/编辑框；id 为空表示新建。 */
    var editing by mutableStateOf<Snippet?>(null)
        private set

    /** 待确认删除的片段。 */
    var pendingDelete by mutableStateOf<Snippet?>(null)
        private set

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            snippets = withContext(Dispatchers.IO) { store.list() }
        }
    }

    fun startAdd() {
        editing = Snippet(id = UUID.randomUUID().toString(), title = "", command = "")
    }

    fun startEdit(snippet: Snippet) {
        editing = snippet
    }

    fun cancelEdit() {
        editing = null
    }

    fun saveEdit(title: String, command: String): Boolean {
        val cur = editing ?: return false
        if (title.isBlank() || command.isBlank()) return false
        val updated = cur.copy(title = title.trim(), command = command.trim())
        editing = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.save(updated) }
            reload()
        }
        return true
    }

    fun confirmDelete(snippet: Snippet) {
        pendingDelete = snippet
    }

    fun cancelDelete() {
        pendingDelete = null
    }

    fun doDelete() {
        val s = pendingDelete ?: return
        pendingDelete = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(s.id) }
            reload()
        }
    }
}

/** 代码片段页：列表、新增/编辑、删除。终端里可一键填入或执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(nav: NavController) {
    val vm: SnippetsViewModel = viewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("代码片段") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::startAdd) {
                Icon(Icons.Filled.Add, contentDescription = "新增片段")
            }
        },
    ) { padding ->
        if (vm.snippets.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "还没有代码片段，点右下角 + 添加",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.snippets, key = { it.id }) { snippet ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(snippet.title) },
                            supportingContent = {
                                Text(
                                    snippet.command,
                                    maxLines = 3,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Column {
                                    IconButton(onClick = { vm.startEdit(snippet) }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                                    }
                                    IconButton(onClick = { vm.confirmDelete(snippet) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    vm.editing?.let { snippet ->
        SnippetEditDialog(
            initial = snippet,
            onSave = vm::saveEdit,
            onDismiss = vm::cancelEdit,
        )
    }

    vm.pendingDelete?.let { snippet ->
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("删除片段") },
            text = { Text("确定删除「${snippet.title}」吗？") },
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

/** 新增 / 编辑片段的对话框。 */
@Composable
private fun SnippetEditDialog(
    initial: Snippet,
    /** 返回 false 表示校验没过（标题/命令为空），对话框保持打开。 */
    onSave: (title: String, command: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var command by remember { mutableStateOf(initial.command) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.title.isBlank() && initial.command.isBlank()) "新增片段" else "编辑片段") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it; error = null },
                    label = { Text("命令") },
                    minLines = 3,
                    maxLines = 8,
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
                if (!onSave(title, command)) error = "标题和命令都不能为空"
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
