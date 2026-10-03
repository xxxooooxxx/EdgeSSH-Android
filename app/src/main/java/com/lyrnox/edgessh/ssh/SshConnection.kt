package com.lyrnox.edgessh.ssh

import android.content.Context
import android.util.Base64
import com.lyrnox.edgessh.data.FingerprintStore
import com.lyrnox.edgessh.data.Host
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * 主机密钥未知时抛出。调用方（终端控制器）捕获后应弹窗让用户确认指纹，
 * 用户确认后用 [FingerprintStore.put] 存下，再重新 connect。
 */
class FingerprintUnknownException(
    val hostname: String,
    val port: Int,
    /** OpenSSH 风格："SHA256:<base64，无换行无padding>" */
    val sha256Fingerprint: String,
    val keyAlgorithm: String,
) : Exception("Unknown host key: $sha256Fingerprint")

/** 连接时用的凭据，由调用方从 CredentialStore 取出后传入（本类不读任何存储）。 */
data class SshCredentials(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val passphrase: String? = null,
)

/**
 * 一个已启动的交互式 shell 通道。
 * stdin/stdout 由终端控制器在 IO 线程上读写；关闭顺序：shell -> session。
 */
data class ShellHandle(
    val session: Session,
    val shell: Session.Shell,
    /** shell.outputStream：写用户按键 */
    val stdin: java.io.OutputStream,
    /** shell.inputStream：读远端输出 */
    val stdout: java.io.InputStream,
)

/**
 * 一台 SSH 主机的连接。同一时间只持有一个 [SSHClient]。
 *
 * 线程约定：connect / disconnect / openShell / openSftp 都是阻塞调用，
 * 必须在 Dispatchers.IO 上调用（connect 内部自己切，openShell/openSftp 由调用方保证）。
 */
class SshConnection(
    private val appContext: Context,
    private val fingerprints: FingerprintStore,
) {
    @Volatile
    private var client: SSHClient? = null

    val isConnected: Boolean
        get() = client?.isConnected == true

    /**
     * 建立连接并完成认证。
     *
     * 流程：主机密钥校验（未知指纹抛 [FingerprintUnknownException]）→ TCP 连接 →
     * 认证（密钥优先，密码失败后自动降级 keyboard-interactive）。
     * 任何一步失败都会关闭半成品连接并把异常抛给调用方。
     */
    suspend fun connect(host: Host, creds: SshCredentials, timeoutMs: Int = 15000) {
        // 先断开旧连接，避免泄漏
        disconnect()
        withContext(Dispatchers.IO) {
            val c = SSHClient()
            try {
                // 1. 主机密钥校验：SHA256(key.encoded) -> base64 -> "SHA256:xxx"
                c.addHostKeyVerifier { hostname, port, key ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                    val b64 = Base64.encodeToString(
                        digest,
                        Base64.NO_WRAP or Base64.NO_PADDING, // 无换行、无末尾 '='
                    )
                    val fp = "SHA256:$b64"
                    val known = fingerprints.get(hostname, port)
                    if (known == null || !known.equals(fp, ignoreCase = true)) {
                        throw FingerprintUnknownException(hostname, port, fp, key.algorithm)
                    }
                    true
                }
                // 契约之外多设一个：socket 建连超时（setTimeout 只管建连后的传输超时）
                c.connectTimeout = timeoutMs
                c.connect(host.hostname, host.port)
                c.timeout = timeoutMs

                // 2. 认证
                if (host.authType == Host.AUTH_KEY && !creds.privateKeyPem.isNullOrBlank()) {
                    // PEM 文本 -> OpenSSHKeyFile（sshj 的 loadKeys 只有文件路径重载）
                    val keyFile = OpenSSHKeyFile()
                    keyFile.init(creds.privateKeyPem, null, passphraseFinder(creds.passphrase))
                    c.authPublickey(host.username, keyFile)
                } else {
                    val pw = creds.password.orEmpty()
                    try {
                        c.authPassword(host.username, pw)
                    } catch (e: UserAuthException) {
                        // password 认证被拒：降级 keyboard-interactive，所有 prompt 都答同一个密码
                        c.auth(host.username, AuthKeyboardInteractive(passwordChallengeProvider(pw)))
                    }
                }
                client = c
            } catch (t: Throwable) {
                // 失败时关掉半成品连接再抛，避免泄漏 socket/线程
                try {
                    c.disconnect()
                } catch (_: Exception) {
                }
                throw t
            }
        }
    }

    /**
     * 开一个交互式 shell（带 xterm-256color PTY）。未连接时抛 IllegalStateException。
     * 注意：这是阻塞调用（含通道建连的网络往返），调用方必须在 IO 线程上调。
     *
     * 终端改尺寸不要调 Session（它没有 reqWindowChange 方法），
     * 调返回的 [ShellHandle.shell].changeWindowDimensions(cols, rows, 0, 0)。
     */
    fun openShell(cols: Int, rows: Int): ShellHandle {
        val c = client
        if (c == null || !c.isConnected) throw IllegalStateException("SSH 未连接")
        // 注意：sshj 的方法是 startSession()，没有 newSession()
        val session = c.startSession()
        try {
            session.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap<PTYMode, Int>())
            val shell = session.startShell()
            return ShellHandle(session, shell, shell.outputStream, shell.inputStream)
        } catch (t: Throwable) {
            try {
                session.close()
            } catch (_: Exception) {
            }
            throw t
        }
    }

    /**
     * 开一个 SFTP 客户端。调用方（SftpManager）负责 close。
     * 未连接时抛 IllegalStateException。阻塞调用，调用方必须在 IO 线程上调。
     */
    fun openSftp(): SFTPClient {
        val c = client
        if (c == null || !c.isConnected) throw IllegalStateException("SSH 未连接")
        return c.newSFTPClient()
    }

    /** 断开连接。幂等，异常内部吞掉。 */
    fun disconnect() {
        val c = client
        client = null
        if (c != null) {
            try {
                c.disconnect()
            } catch (_: Exception) {
                // 断开时不抛异常
            }
        }
    }

    /** 私钥 passphrase 的 PasswordFinder：只给一次，不重试。 */
    private fun passphraseFinder(passphrase: String?): PasswordFinder =
        object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray =
                passphrase?.toCharArray() ?: CharArray(0)

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }

    /** keyboard-interactive 的 provider：所有 prompt 一律回答同一个密码，不重试。 */
    private fun passwordChallengeProvider(password: String): ChallengeResponseProvider =
        object : ChallengeResponseProvider {
            override fun getSubmethods(): List<String> = emptyList()

            override fun init(resource: Resource<*>?, name: String?, instruction: String?) {
                // 不需要初始化
            }

            override fun getResponse(prompt: String?, echo: Boolean): CharArray =
                password.toCharArray()

            override fun shouldRetry(): Boolean = false
        }
}
