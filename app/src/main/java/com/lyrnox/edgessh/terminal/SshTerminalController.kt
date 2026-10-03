package com.lyrnox.edgessh.terminal

import com.lyrnox.edgessh.ssh.ShellHandle
import com.lyrnox.edgessh.ssh.SshConnection
import com.termux.terminal.SshTermBridge
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * SSH 终端控制器：把 sshj 的 shell 通道和 Termux 的 TerminalSession 双向桥接。
 *
 * 用法：
 * ```
 * val controller = SshTerminalController(conn, sessionClient)
 * terminalView.attachSession(controller.termSession)
 * controller.start(cols, rows)
 * // view 尺寸变化时：
 * controller.sendWindowChange(newCols, newRows)
 * // 退出时：
 * controller.close()
 * ```
 */
class SshTerminalController(
    private val conn: SshConnection,
    sessionClient: TerminalSessionClient,
    transcriptRows: Int = 5000,
) {
    val termSession: TerminalSession =
        SshTermBridge.createSession(sessionClient, transcriptRows)

    @Volatile
    private var running = false
    private var shell: ShellHandle? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    /** 远端断开时的回调（在 IO 线程调用，UI 侧自行切主线程）。 */
    var onDisconnected: (() -> Unit)? = null

    fun start(cols: Int, rows: Int) {
        val sh = conn.openShell(cols, rows)
        shell = sh
        running = true

        readerThread = Thread({
            val buf = ByteArray(8192)
            try {
                while (running) {
                    val n = sh.stdout.read(buf)
                    if (n == -1) break
                    if (n > 0) SshTermBridge.feedOutput(termSession, buf, 0, n)
                }
            } catch (_: Exception) {
                // 连接断开，见 finally
            } finally {
                handleRemoteClosed()
            }
        }, "SshTermReader").apply { isDaemon = true; start() }

        writerThread = Thread({
            val buf = ByteArray(4096)
            try {
                while (running) {
                    val n = SshTermBridge.readInput(termSession, buf)
                    if (n == -1) break
                    if (n > 0) {
                        sh.stdin.write(buf, 0, n)
                        sh.stdin.flush()
                    }
                }
            } catch (_: Exception) {
                // 连接断开
            }
        }, "SshTermWriter").apply { isDaemon = true; start() }
    }

    /** 终端尺寸变化时通知远端（对应 ssh window-change）。 */
    fun sendWindowChange(cols: Int, rows: Int) {
        try {
            // 注意：sshj 里 window-change 在 Session.Shell 上，不在 Session 上
            shell?.shell?.changeWindowDimensions(cols, rows, 0, 0)
        } catch (_: Exception) {
        }
    }

    /** 当前终端的列数/行数（用于轮询检测尺寸变化）。 */
    fun currentCols(): Int = SshTermBridge.columns(termSession)
    fun currentRows(): Int = SshTermBridge.rows(termSession)

    private fun handleRemoteClosed() {
        if (!running) return
        running = false
        val msg = "\r\n[SSH 连接已断开]\r\n".toByteArray()
        SshTermBridge.feedOutput(termSession, msg, 0, msg.size)
        onDisconnected?.invoke()
    }

    fun close() {
        running = false
        try {
            shell?.shell?.close()
        } catch (_: Exception) {
        }
        try {
            shell?.session?.close()
        } catch (_: Exception) {
        }
        SshTermBridge.close(termSession)
        try {
            conn.disconnect()
        } catch (_: Exception) {
        }
    }
}
