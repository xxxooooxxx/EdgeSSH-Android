package com.lyrnox.edgessh.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lyrnox.edgessh.data.CredentialStore
import com.lyrnox.edgessh.data.Host
import com.lyrnox.edgessh.ssh.SshCredentials

/** 路由表。 */
object Routes {
    const val HOSTS = "hosts"
    const val HOST_EDIT = "hostEdit?id={id}"
    const val TERMINAL = "terminal/{hostId}"
    const val FILES = "files/{hostId}"
    const val SNIPPETS = "snippets"

    /** id 缺省为 "new" 表示新建。 */
    fun hostEdit(id: String = "new") = "hostEdit?id=$id"
    fun terminal(hostId: String) = "terminal/$hostId"
    fun files(hostId: String) = "files/$hostId"
}

/** 应用导航。 */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOSTS) {
        composable(Routes.HOSTS) {
            HostListScreen(nav)
        }
        composable(
            route = Routes.HOST_EDIT,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    defaultValue = "new"
                },
            ),
        ) { entry ->
            HostEditScreen(nav, entry.arguments?.getString("id") ?: "new")
        }
        composable(Routes.TERMINAL) { entry ->
            val hostId = entry.arguments?.getString("hostId") ?: return@composable
            TerminalScreen(nav, hostId)
        }
        composable(Routes.FILES) { entry ->
            val hostId = entry.arguments?.getString("hostId") ?: return@composable
            FileManagerScreen(nav, hostId)
        }
        composable(Routes.SNIPPETS) {
            SnippetsScreen(nav)
        }
    }
}

/**
 * 给带 hostId 参数的 AndroidViewModel 用的 Factory。
 * 无参的 AndroidViewModel（HostList/Snippets）直接用默认 factory 的 viewModel() 即可。
 */
class HostScopedFactory(
    private val app: Application,
    private val hostId: String,
    private val create: (Application, String) -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        create(app, hostId) as T
}

/** 按主机的认证方式从 CredentialStore 组装登录凭据，缺凭据时抛错。 */
internal fun buildSshCredentials(context: Context, host: Host): SshCredentials {
    val store = CredentialStore(context.applicationContext)
    return if (host.authType == Host.AUTH_KEY) {
        val pem = store.getPrivateKey(host.id)
            ?: throw IllegalStateException("未找到该主机的私钥，请先编辑主机补上")
        SshCredentials(
            password = null,
            privateKeyPem = pem,
            passphrase = store.getPassphrase(host.id),
        )
    } else {
        val pw = store.getPassword(host.id)
            ?: throw IllegalStateException("未找到该主机的密码，请先编辑主机补上")
        SshCredentials(
            password = pw,
            privateKeyPem = null,
            passphrase = null,
        )
    }
}
