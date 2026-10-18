package org.fossify.clock.ha

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ativação pelo painel Despertador: troca o código de uso único por webhook + token gerados pelo HA. */
object HaActivation {
    const val PANEL_PATH = "despertador"
    private val URL_REGEX = Regex("^https?://[^\\s/]+(/[^\\s]*)?$")
    private val CODE_REGEX = Regex("^[A-Za-z0-9]{6,32}$")

    data class Request(val url: String, val code: String)

    /** Lê `alarmeha://ativar?url=...&codigo=...`. */
    fun parse(uri: Uri?): Request? {
        if (uri == null || uri.scheme != "alarmeha" || uri.host != "ativar") return null
        return validate(uri.getQueryParameter("url"), uri.getQueryParameter("codigo"))
    }

    /** Lê o texto de pareamento v2 `{"v":2,"url":...,"codigo":...}`. */
    fun parse(json: JSONObject): Request? =
        if (json.optInt("v") == 2) validate(json.optString("url"), json.optString("codigo")) else null

    private fun validate(url: String?, code: String?): Request? {
        val cleanUrl = url.orEmpty().trim().trimEnd('/')
        val cleanCode = code.orEmpty().trim()
        if (!cleanUrl.matches(URL_REGEX) || !cleanCode.matches(CODE_REGEX)) return null
        return Request(cleanUrl, cleanCode)
    }

    /** Faz a troca (bloqueante: chamar fora da thread principal). Retorna null em caso de sucesso ou a mensagem de erro. */
    fun redeem(context: Context, request: Request): String? {
        val body = JSONObject().apply {
            put("codigo", request.code)
            put("pacote", context.packageName)
            put("dispositivo", HaWebhook.deviceName(context))
        }
        val response = try {
            val connection = (URL("${request.url}/api/alarmeha/ativar").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            when (code) {
                in 200..299 -> text
                403 -> return "Código inválido ou expirado. Toque em Ativar de novo no painel Despertador."
                429 -> return "Muitas tentativas. Aguarde alguns minutos."
                404 -> return "A integração AlarmeHA não está instalada nesse Home Assistant (ou está desatualizada)."
                else -> return "O Home Assistant respondeu com erro $code."
            }
        } catch (e: Exception) {
            return "Não foi possível falar com ${request.url}: ${e.message ?: e.javaClass.simpleName}"
        }

        val data = runCatching { JSONObject(response) }.getOrNull() ?: return "Resposta inválida do Home Assistant."
        val webhook = data.optString("webhook")
        val token = data.optString("token")
        if (!webhook.matches(Regex("^[A-Za-z0-9_-]{8,128}$")) || token.length < 20) {
            return "Resposta inválida do Home Assistant."
        }

        val config = HaConfig(context)
        config.haUrl = request.url
        config.webhookId = webhook
        config.personName = data.optString("nome").trim().take(40)
        config.setServerToken(token)
        config.rejectedCommands = 0
        config.onboardingDismissed = true
        HaWebhook.send(context, "pareado", JSONObject().apply {
            put("pedido", JSONObject.NULL)
            put("token_definido", true)
        })
        return null
    }
}
