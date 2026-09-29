package com.mycamerascontroller.agent

import android.content.Context
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

// Kotlin mirror of packages/shared/src/network.ts: the network (the owner's
// own Firebase project + an unguessable network id + optional TURN) is
// entered at first launch, never baked into the APK — one build works for
// everyone, and each user's devices only ever see their own network.
// Keep this file identical between android-agent and android-client
// (only the package line differs).

data class TurnServer(val urls: List<String>, val username: String?, val credential: String?)

data class NetworkConfig(
    val name: String,
    val apiKey: String,
    val authDomain: String,
    val databaseUrl: String,
    val projectId: String,
    val appId: String,
    val networkId: String,
    val turnWorkerUrl: String?,
    val turnServers: List<TurnServer>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("name", name)
        put("firebase", JSONObject().apply {
            put("apiKey", apiKey); put("authDomain", authDomain); put("databaseURL", databaseUrl)
            put("projectId", projectId); put("appId", appId)
        })
        put("networkId", networkId)
        if (turnWorkerUrl != null || turnServers.isNotEmpty()) {
            put("turn", JSONObject().apply {
                turnWorkerUrl?.let { put("workerUrl", it) }
                if (turnServers.isNotEmpty()) put("servers", JSONArray().apply {
                    turnServers.forEach { s ->
                        put(JSONObject().apply {
                            put("urls", JSONArray(s.urls))
                            s.username?.let { put("username", it) }
                            s.credential?.let { put("credential", it) }
                        })
                    }
                })
            })
        }
    }
}

class NetworkException(message: String) : Exception(message)

object Network {
    private const val PREFS = "network"
    private const val KEY = "config"
    private const val INVITE_PREFIX = "CC1."

    @Volatile var current: NetworkConfig? = null
        private set

    fun load(context: Context): NetworkConfig? {
        current?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            parse(JSONObject(raw)).also { current = it }
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, cfg: NetworkConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, cfg.toJson().toString()).commit()
        current = cfg
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
        current = null
        FirebaseApp.getApps(context).forEach { runCatching { it.delete() } }
    }

    // ---------- invite codes ----------

    fun encodeInvite(cfg: NetworkConfig): String =
        INVITE_PREFIX + Base64.encodeToString(cfg.toJson().toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun decodeInvite(code: String): NetworkConfig {
        val clean = code.replace(Regex("\\s+"), "")
        if (!clean.startsWith(INVITE_PREFIX)) throw NetworkException("Это не код приглашения CamerasController")
        val json = try {
            String(Base64.decode(clean.removePrefix(INVITE_PREFIX), Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw NetworkException("Код повреждён — скопируйте его целиком")
        }
        return try {
            parse(JSONObject(json))
        } catch (e: NetworkException) {
            throw e
        } catch (e: Exception) {
            throw NetworkException("Код повреждён — скопируйте его целиком")
        }
    }

    private fun parse(o: JSONObject): NetworkConfig {
        if (o.optInt("v") != 1) throw NetworkException("Неподдерживаемая версия конфигурации сети")
        val fb = o.optJSONObject("firebase") ?: throw NetworkException("В конфигурации сети нет данных Firebase")
        fun req(k: String) = fb.optString(k).takeIf { it.isNotBlank() } ?: throw NetworkException("В конфиге Firebase нет поля $k")
        val networkId = o.optString("networkId")
        if (!Regex("^[a-f0-9]{32}$").matches(networkId)) throw NetworkException("Некорректный идентификатор сети")
        val turn = o.optJSONObject("turn")
        val servers = mutableListOf<TurnServer>()
        turn?.optJSONArray("servers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val urlsField = s.get("urls")
                val urls = if (urlsField is JSONArray) (0 until urlsField.length()).map { urlsField.getString(it) } else listOf(urlsField.toString())
                servers += TurnServer(urls, s.optString("username").ifBlank { null }, s.optString("credential").ifBlank { null })
            }
        }
        val databaseUrl = req("databaseURL")
        if (!databaseUrl.startsWith("https://")) throw NetworkException("databaseURL должен начинаться с https://")
        return NetworkConfig(
            name = o.optString("name").ifBlank { "Моя сеть" }.take(60),
            apiKey = req("apiKey"), authDomain = req("authDomain"), databaseUrl = databaseUrl.trimEnd('/'),
            projectId = req("projectId"), appId = req("appId"),
            networkId = networkId,
            turnWorkerUrl = turn?.optString("workerUrl")?.takeIf { it.startsWith("https://") },
            turnServers = servers,
        )
    }

    /** Accepts JSON or the JS snippet from the Firebase console. */
    fun parseFirebaseSnippet(name: String, text: String, turnWorkerUrl: String?): NetworkConfig {
        val fields = Regex("[\"']?(\\w+)[\"']?\\s*:\\s*[\"']([^\"']*)[\"']").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val required = listOf("apiKey", "authDomain", "databaseURL", "projectId", "appId")
        val missing = required.filter { fields[it].isNullOrBlank() }
        if ("databaseURL" in missing && fields["projectId"] != null) {
            throw NetworkException("В конфиге нет databaseURL. Создайте Realtime Database и скопируйте конфиг заново.")
        }
        if (missing.isNotEmpty()) throw NetworkException("В конфиге не хватает полей: ${missing.joinToString()}")
        val cfg = JSONObject().apply {
            put("v", 1)
            put("name", name.ifBlank { "Моя сеть" })
            put("firebase", JSONObject().apply { required.forEach { put(it, fields[it]) } })
            put("networkId", newNetworkId())
            turnWorkerUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (!it.startsWith("https://")) throw NetworkException("Адрес TURN-воркера должен начинаться с https://")
                put("turn", JSONObject().put("workerUrl", it.trimEnd('/')))
            }
        }
        return parse(cfg)
    }

    fun newNetworkId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------- Firebase ----------

    /**
     * Initializes the default FirebaseApp from the network config — there is
     * no google-services.json in this build. A web app's config works here:
     * Realtime Database and anonymous Auth only need the API key, project,
     * app id and database URL.
     */
    fun ensureFirebase(context: Context, cfg: NetworkConfig) {
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) {
            if (existing.options.databaseUrl == cfg.databaseUrl && existing.options.apiKey == cfg.apiKey) return
            existing.delete()
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(cfg.apiKey)
            .setApplicationId(cfg.appId)
            .setProjectId(cfg.projectId)
            .setDatabaseUrl(cfg.databaseUrl)
            .build()
        FirebaseApp.initializeApp(context.applicationContext, options)
    }

    /**
     * Setup-time check: sign in and read this network's node, turning the
     * usual Firebase-console mistakes into a message saying what to fix.
     * Calls back on the main thread with null on success.
     */
    fun test(context: Context, cfg: NetworkConfig, cb: (String?) -> Unit) {
        try {
            ensureFirebase(context, cfg)
        } catch (e: Exception) {
            cb("Некорректный конфиг Firebase: ${e.message}"); return
        }
        FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener { auth ->
            if (!auth.isSuccessful) {
                val code = (auth.exception as? FirebaseAuthException)?.errorCode ?: ""
                cb(
                    when {
                        code.contains("OPERATION_NOT_ALLOWED") || code.contains("ADMIN_ONLY") ->
                            "В Firebase не включён анонимный вход: Authentication → Sign-in method → Anonymous → Enable."
                        auth.exception?.message?.contains("CONFIGURATION_NOT_FOUND") == true ->
                            "В проекте не настроена Authentication: откройте Authentication → Get started и включите Anonymous."
                        else -> "Не удалось войти в Firebase: ${auth.exception?.message}"
                    }
                )
                return@addOnCompleteListener
            }
            FirebaseDatabase.getInstance().getReference("rooms/${cfg.networkId}/devices").get().addOnCompleteListener { read ->
                if (read.isSuccessful) cb(null)
                else if (read.exception?.message?.contains("ermission") == true)
                    cb("База отклонила запрос: вставьте правила из firebase/database.rules.json в Realtime Database → Rules и нажмите Publish.")
                else cb("Нет доступа к Realtime Database (${read.exception?.message}). Проверьте databaseURL.")
            }
        }
    }
}
