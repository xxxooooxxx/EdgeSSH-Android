package com.lyrnox.edgessh.ssh

import java.io.File
import java.io.InputStream
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.FileSystemFile
import net.schmizz.sshj.xfer.LocalFileFilter
import net.schmizz.sshj.xfer.LocalSourceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SFTP 文件管理。内部懒持有单个 [SFTPClient]（[SshConnection.openSftp]），
 * 所有操作都在 Dispatchers.IO 上执行。
 *
 * 注意：[SshConnection.disconnect] 后这个 manager 的 client 即失效，
 * 调用方应在重连后重新 new SftpManager 或先调 [close]。
 */
class SftpManager(private val conn: SshConnection) {

    data class Entry(
        val name: String,
        /** 完整远端路径，如 "/home/user/a.txt" */
        val path: String,
        val isDir: Boolean,
        val size: Long,
        /** 毫秒时间戳（远端 mtime 秒 -> 毫秒） */
        val lastModified: Long,
    )

    private val lock = Any()

    @Volatile
    private var sftp: SFTPClient? = null

    /** 懒获取 SFTPClient。必须在 IO 线程上调用（openSftp 含建通道的网络往返）。 */
    private fun client(): SFTPClient = synchronized(lock) {
        sftp ?: conn.openSftp().also { sftp = it }
    }

    /** 列出目录：目录优先、按名称排序，跳过 "." 和 ".."。 */
    suspend fun listDir(path: String): List<Entry> = withContext(Dispatchers.IO) {
        client().ls(path)
            .asSequence()
            .filter { it.name != "." && it.name != ".." }
            .map { info ->
                Entry(
                    name = info.name,
                    path = joinPath(path, info.name),
                    isDir = info.isDirectory,
                    size = info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                )
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            .toList()
    }

    /** 下载远端文件到本地。本地父目录不存在会自动创建。 */
    suspend fun download(remotePath: String, localFile: File) = withContext(Dispatchers.IO) {
        localFile.parentFile?.mkdirs()
        // SFTPClient 没有 get(remote, OutputStream) 重载，走 fileTransfer + FileSystemFile
        client().fileTransfer.download(remotePath, FileSystemFile(localFile))
    }

    /** 把输入流上传为远端文件（覆盖）。流长度未知时按流式传输。 */
    suspend fun uploadStream(input: InputStream, remotePath: String) = withContext(Dispatchers.IO) {
        // SFTPClient 没有 put(InputStream, remote) 重载，走 fileTransfer + 轻量包装类
        val name = remotePath.substringAfterLast('/').ifEmpty { "upload" }
        client().fileTransfer.upload(StreamSourceFile(input, name), remotePath)
    }

    /** 建目录（只建一级）。 */
    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        client().mkdir(path)
    }

    /** 重命名/移动。 */
    suspend fun rename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        client().rename(oldPath, newPath)
    }

    /** 删除：文件直接 rm；目录先递归删内容再 rmdir。 */
    suspend fun delete(entry: Entry) = withContext(Dispatchers.IO) {
        val c = client()
        if (entry.isDir) {
            c.ls(entry.path)
                .filter { it.name != "." && it.name != ".." }
                .forEach { info ->
                    delete(
                        Entry(
                            name = info.name,
                            path = joinPath(entry.path, info.name),
                            isDir = info.isDirectory,
                            size = info.attributes.size,
                            lastModified = info.attributes.mtime * 1000L,
                        ),
                    )
                }
            c.rmdir(entry.path)
        } else {
            c.rm(entry.path)
        }
    }

    /** 关闭持有的 SFTPClient。幂等。 */
    fun close() {
        synchronized(lock) {
            try {
                sftp?.close()
            } catch (_: Exception) {
                // 关闭时不抛异常
            }
            sftp = null
        }
    }

    companion object {
        /** 拼接远端路径，注意 parent 为 "/" 时不产生 "//"。 */
        private fun joinPath(parent: String, name: String): String {
            val p = parent.trimEnd('/')
            return "$p/$name" // parent="/" -> "/name"
        }
    }
}

/**
 * 把任意 InputStream 包装成 sshj 的 LocalSourceFile，用于流式上传。
 * getLength() 返回 -1（长度未知，只用于默认的日志型进度监听，安全）。
 */
private class StreamSourceFile(
    private val input: InputStream,
    private val name: String,
) : LocalSourceFile {
    override fun getName(): String = name

    override fun getLength(): Long = -1L

    override fun getInputStream(): InputStream = input

    override fun getPermissions(): Int = 0x180 // 0600

    override fun isFile(): Boolean = true

    override fun isDirectory(): Boolean = false

    override fun getChildren(filter: LocalFileFilter?): Iterable<LocalSourceFile> = emptyList()

    override fun providesAtimeMtime(): Boolean = false

    override fun getLastAccessTime(): Long = 0L

    override fun getLastModifiedTime(): Long = 0L
}
