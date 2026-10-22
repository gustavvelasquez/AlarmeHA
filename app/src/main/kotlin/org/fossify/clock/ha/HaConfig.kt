package org.fossify.clock.ha

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom

/** Configuração do pareamento com o Home Assistant (AlarmeHA). O token nunca é guardado, só o hash SHA-256.
 * Na ativação pelo painel o token vem do HA; no modo avançado (QR v1) ele é gerado aqui e mostrado uma vez. */
class HaConfig(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var haUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trimEnd('/')).apply()

    /** Todas as URLs conhecidas do HA (origem da ativação, externa, interna). [haUrl] é a última que funcionou. */
    var urls: List<String>
        get() = runCatching {
            val array = JSONArray(prefs.getString(KEY_URLS, "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString(KEY_URLS, JSONArray(value.map { it.trimEnd('/') }.distinct()).toString()).apply()

    /** Ordem de tentativa do webhook: a última URL que funcionou primeiro, depois as demais. */
    val candidateUrls: List<String>
        get() = (listOf(haUrl) + urls).filter { it.isNotEmpty() }.distinct()

    fun webhookUrlFor(base: String) = "$base/api/webhook/$webhookId"

    var webhookId: String
        get() = prefs.getString(KEY_WEBHOOK, "") ?: ""
        set(value) {
            // Webhook novo (pareado de novo): a fila do webhook antigo não vale mais.
            if (value != webhookId) HaWebhook.clear(appContext)
            prefs.edit().putString(KEY_WEBHOOK, value).commit()
        }

    // ---- diagnóstico do envio (Sobre / tela do HA) ----
    var lastOkAt: Long
        get() = prefs.getLong(KEY_OK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_OK_AT, value).apply()
    var lastOkUrl: String
        get() = prefs.getString(KEY_OK_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OK_URL, value).apply()
    var lastError: String
        get() = prefs.getString(KEY_ERROR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ERROR, value.take(1000)).apply()
    var lastErrorAt: Long
        get() = prefs.getLong(KEY_ERROR_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_ERROR_AT, value).apply()
    var droppedEvents: Int
        get() = prefs.getInt(KEY_DROPPED, 0)
        set(value) = prefs.edit().putInt(KEY_DROPPED, value).apply()

    /** Modo diagnóstico (Configurações): desligado por padrão; nunca desliga sozinho. */
    var diagnosticMode: Boolean
        get() = prefs.getBoolean(KEY_DIAG, false)
        set(value) = prefs.edit().putBoolean(KEY_DIAG, value).apply()

    var personName: String
        get() = prefs.getString(KEY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    private var tokenHash: String
        get() = prefs.getString(KEY_TOKEN_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN_HASH, value).apply()

    var rejectedCommands: Int
        get() = prefs.getInt(KEY_REJECTED, 0)
        set(value) = prefs.edit().putInt(KEY_REJECTED, value).apply()

    val hasWebhook get() = haUrl.isNotEmpty() && webhookId.isNotEmpty()
    val hasToken get() = tokenHash.isNotEmpty()
    val isPaired get() = hasWebhook && hasToken

    val webhookUrl get() = "$haUrl/api/webhook/$webhookId"

    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        tokenHash = sha256(token)
        return token
    }

    /** Guarda o token gerado pelo Home Assistant na ativação (só o hash). */
    fun setServerToken(token: String) {
        tokenHash = sha256(token)
    }

    /** Já foi ativado alguma vez. A tela de ativação obrigatória só aparece enquanto isso for falso:
     * depois de ativado, o app e os alarmes funcionam sempre, mesmo sem HA, internet ou token válido. */
    val everActivated: Boolean
        get() = prefs.getBoolean(KEY_EVER_ACTIVATED, false) || isPaired

    fun markActivated() {
        prefs.edit().putBoolean(KEY_EVER_ACTIVATED, true).apply()
    }

    fun isValidToken(token: String?): Boolean {
        val stored = tokenHash
        if (token.isNullOrEmpty() || stored.isEmpty()) return false
        return MessageDigest.isEqual(
            sha256(token).toByteArray(Charsets.US_ASCII),
            stored.toByteArray(Charsets.US_ASCII)
        )
    }

    fun unpair() {
        HaWebhook.clear(appContext)
        val activated = everActivated
        val diag = diagnosticMode
        prefs.edit().clear().putBoolean(KEY_EVER_ACTIVATED, activated).putBoolean(KEY_DIAG, diag).apply()
    }

    companion object {
        private const val PREFS = "alarmeha_home_assistant"
        private const val KEY_URL = "ha_url"
        private const val KEY_WEBHOOK = "webhook_id"
        private const val KEY_URLS = "ha_urls"
        private const val KEY_NAME = "person_name"
        private const val KEY_TOKEN_HASH = "token_sha256"
        private const val KEY_REJECTED = "rejected_commands"
        private const val KEY_EVER_ACTIVATED = "ever_activated"
        private const val KEY_OK_AT = "envio_ok_em"
        private const val KEY_OK_URL = "envio_ok_url"
        private const val KEY_ERROR = "envio_erro"
        private const val KEY_ERROR_AT = "envio_erro_em"
        private const val KEY_DROPPED = "envio_descartados"
        private const val KEY_DIAG = "modo_diagnostico"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
