package com.lyrnox.edgessh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** 加密 SharedPreferences（Android Keystore 持有主密钥）。 */
internal fun securePrefs(context: Context, name: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context.applicationContext,
        name,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/** 主机密码 / 私钥 / passphrase，按 host id 加密存放。 */
class CredentialStore(context: Context) {
    private val prefs = securePrefs(context, "edgessh_creds")

    fun setPassword(hostId: String, password: String) {
        prefs.edit().putString("pw_$hostId", password).apply()
    }

    fun getPassword(hostId: String): String? = prefs.getString("pw_$hostId", null)

    fun setPrivateKey(hostId: String, pem: String, passphrase: String?) {
        prefs.edit()
            .putString("key_$hostId", pem)
            .putString("pp_$hostId", passphrase)
            .apply()
    }

    fun getPrivateKey(hostId: String): String? = prefs.getString("key_$hostId", null)

    fun getPassphrase(hostId: String): String? = prefs.getString("pp_$hostId", null)

    fun clear(hostId: String) {
        prefs.edit()
            .remove("pw_$hostId")
            .remove("key_$hostId")
            .remove("pp_$hostId")
            .apply()
    }
}

/** 主机列表（含非敏感字段），整体加密存放。 */
class HostStore(context: Context) {
    private val prefs = securePrefs(context, "edgessh_hosts")

    fun list(): List<Host> {
        val raw = prefs.getString("hosts_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.getJSONObject(i).toHost() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Host? = list().firstOrNull { it.id == id }

    fun save(host: Host) {
        val updated = list().filterNot { it.id == host.id } + host
        persist(updated)
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
    }

    private fun persist(hosts: List<Host>) {
        val arr = JSONArray()
        hosts.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("hosts_json", arr.toString()).apply()
    }

    private fun Host.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("hostname", hostname)
        .put("port", port)
        .put("username", username)
        .put("authType", authType)
        .put("group", group)

    private fun JSONObject.toHost(): Host = Host(
        id = getString("id"),
        name = getString("name"),
        hostname = getString("hostname"),
        port = optInt("port", 22),
        username = getString("username"),
        authType = optString("authType", Host.AUTH_PASSWORD),
        group = optString("group", ""),
    )
}

/** 已确认的主机密钥指纹（known_hosts 等价物），加密存放。 */
class FingerprintStore(context: Context) {
    private val prefs = securePrefs(context, "edgessh_hosts")

    private fun key(hostname: String, port: Int) = "fp_${hostname.lowercase()}_$port"

    fun get(hostname: String, port: Int): String? =
        prefs.getString(key(hostname, port), null)

    fun put(hostname: String, port: Int, sha256Hex: String) {
        prefs.edit().putString(key(hostname, port), sha256Hex).apply()
    }
}

/** 命令片段，普通 SharedPreferences 存放。 */
class SnippetStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("edgessh_snippets", Context.MODE_PRIVATE)

    fun list(): List<Snippet> {
        val raw = prefs.getString("snippets_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Snippet(o.getString("id"), o.getString("title"), o.getString("command"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(snippet: Snippet) {
        val updated = list().filterNot { it.id == snippet.id } + snippet
        persist(updated)
    }

    fun delete(id: String) = persist(list().filterNot { it.id == id })

    private fun persist(snippets: List<Snippet>) {
        val arr = JSONArray()
        snippets.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("command", it.command))
        }
        prefs.edit().putString("snippets_json", arr.toString()).apply()
    }
}
