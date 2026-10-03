package com.lyrnox.edgessh.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lyrnox.edgessh.data.CredentialStore
import com.lyrnox.edgessh.data.Host
import com.lyrnox.edgessh.data.HostStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主机新增 / 编辑页（id == "new" 为新建）。
 * 直接用 Compose state，不单独建 ViewModel；保存走 IO 线程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as Application
    val scope = rememberCoroutineScope()
    val isNew = id == "new"

    var name by rememberSaveable { mutableStateOf("") }
    var hostname by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("root") }
    var authType by rememberSaveable { mutableStateOf(Host.AUTH_PASSWORD) }
    var password by rememberSaveable { mutableStateOf("") }
    var privateKey by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(isNew) }

    // 编辑时回填已有数据（含已存凭据，避免改个别字段时把凭据清掉）
    LaunchedEffect(id) {
        if (!isNew) {
            val h = withContext(Dispatchers.IO) { HostStore(app).get(id) }
            if (h == null) {
                nav.popBackStack()
                return@LaunchedEffect
            }
            name = h.name
            hostname = h.hostname
            port = h.port.toString()
            username = h.username
            authType = h.authType
            group = h.group
            withContext(Dispatchers.IO) {
                val creds = CredentialStore(app)
                password = creds.getPassword(id) ?: ""
                privateKey = creds.getPrivateKey(id) ?: ""
                passphrase = creds.getPassphrase(id) ?: ""
            }
            loaded = true
        }
    }

    fun doSave() {
        val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
        if (name.isBlank() || hostname.isBlank() || username.isBlank()) {
            error = "请填写名称、地址和用户名"
            return
        }
        if (portNum == null) {
            error = "端口号不合法（1-65535）"
            return
        }
        if (authType == Host.AUTH_PASSWORD && password.isBlank()) {
            error = "请填写密码"
            return
        }
        if (authType == Host.AUTH_KEY && privateKey.isBlank()) {
            error = "请粘贴私钥（PEM 格式）"
            return
        }
        error = null
        val host = Host(
            id = if (isNew) UUID.randomUUID().toString() else id,
            name = name.trim(),
            hostname = hostname.trim(),
            port = portNum,
            username = username.trim(),
            authType = authType,
            group = group.trim(),
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                HostStore(app).save(host)
                val creds = CredentialStore(app)
                // 先清掉旧凭据（切换认证方式时不留残留），再写新的
                creds.clear(host.id)
                if (authType == Host.AUTH_PASSWORD) {
                    creds.setPassword(host.id, password)
                } else {
                    creds.setPrivateKey(host.id, privateKey.trim(), passphrase.ifBlank { null })
                }
            }
            nav.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新增主机" else "编辑主机") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) {
            // 编辑数据还没读完时不渲染表单，避免闪一下空表单
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("名称（备注）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = hostname, onValueChange = { hostname = it },
                label = { Text("地址（IP 或域名）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = port, onValueChange = { port = it },
                label = { Text("端口") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            // 认证方式：密码 / 密钥
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = authType == Host.AUTH_PASSWORD,
                    onClick = { authType = Host.AUTH_PASSWORD },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("密码") }
                SegmentedButton(
                    selected = authType == Host.AUTH_KEY,
                    onClick = { authType = Host.AUTH_KEY },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("密钥") }
            }
            Spacer(Modifier.height(12.dp))
            if (authType == Host.AUTH_PASSWORD) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = privateKey, onValueChange = { privateKey = it },
                    label = { Text("私钥（PEM）") },
                    minLines = 4, maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("私钥口令（可空）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = group, onValueChange = { group = it },
                label = { Text("分组（可空）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = ::doSave, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
        }
    }
}
