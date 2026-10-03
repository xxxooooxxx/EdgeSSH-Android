package com.termux.terminal

/**
 * 把 Termux 的 [TerminalSession] 接到 SSH 通道，而不是本地 pty。
 *
 * 原理：[TerminalSession] 的构造器本身不 fork（fork 发生在 initializeEmulator，
 * 只有 updateSize 在 mEmulator == null 时才会调它）。我们用公开构造器创建 session，
 * 把 [TerminalSession.mShellPid] 设为 1（>0 即可让 write()/writeCodePoint()
 * 把用户按键排进 mTerminalToProcessIOQueue），再手动注入 [TerminalEmulator]。
 * 之后：
 * - 远端输出：SSH 读线程 -> [feedOutput] -> mProcessToTerminalIOQueue
 *   -> 主线程 handler -> emulator.append -> 刷新屏幕；
 * - 用户输入：TerminalView -> session.write()/writeCodePoint() -> mTerminalToProcessIOQueue，
 *   由调用方开线程用 [readInput] 阻塞读出，写进 SSH stdin。
 *
 * 注意：此类必须放在 com.termux.terminal 包下才能访问包级可见的成员。
 */
object SshTermBridge {

    /** TerminalSession.MainThreadHandler.MSG_NEW_INPUT 的值（该常量是 private，此处硬编码）。 */
    private const val MSG_NEW_INPUT = 1

    fun createSession(
        client: TerminalSessionClient,
        transcriptRows: Int = 5000,
    ): TerminalSession {
        val session = TerminalSession(null, null, null, null, transcriptRows, client)
        session.mShellPid = 1
        session.mEmulator =
            TerminalEmulator(session, 80, 24, 0, 0, transcriptRows, client)
        return session
    }

    /** 把远端发来的字节喂给终端显示（可在任意线程调用）。 */
    fun feedOutput(
        session: TerminalSession,
        data: ByteArray,
        offset: Int = 0,
        count: Int = data.size - offset,
    ) {
        if (session.mProcessToTerminalIOQueue.write(data, offset, count)) {
            session.mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
        }
    }

    /**
     * 阻塞读取用户输入；队列被 close 时返回 -1。
     * 调用方应起独立线程循环调用，把读到的字节写进 SSH 通道的 stdin。
     */
    fun readInput(session: TerminalSession, buf: ByteArray): Int =
        session.mTerminalToProcessIOQueue.read(buf, true)

    /**
     * 当前终端列数/行数（v0.118.3 的 TerminalEmulator 用公开字段 mColumns/mRows，
     * 没有 getter；本函数在同包内直接读取）。
     */
    fun columns(session: TerminalSession): Int = session.mEmulator?.mColumns ?: 80
    fun rows(session: TerminalSession): Int = session.mEmulator?.mRows ?: 24

    fun close(session: TerminalSession) {
        session.mShellPid = -1
        session.mTerminalToProcessIOQueue.close()
        session.mProcessToTerminalIOQueue.close()
    }
}
