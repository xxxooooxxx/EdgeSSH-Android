package com.lyrnox.edgessh.data

import java.util.UUID

/**
 * 一台 SSH 主机。密码 / 私钥等敏感字段不放在这里，
 * 由 [CredentialStore] 按 host id 加密保存。
 */
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: String = AUTH_PASSWORD,
    val group: String = "",
) {
    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }
}
