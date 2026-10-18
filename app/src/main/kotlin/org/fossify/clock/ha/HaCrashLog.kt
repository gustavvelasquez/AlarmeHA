package org.fossify.clock.ha

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda o último erro fatal do app num arquivo local, para aparecer na tela Home Assistant (diagnóstico). */
object HaCrashLog {
    private const val FILE = "alarmeha_ultimo_erro.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, FILE).writeText("${HaWebhook.now()} [${thread.name}]\n$trace".take(8000))
            }
            runCatching { writeToDownloads(app, "${java.util.Date()} [${thread.name}] v${org.fossify.clock.BuildConfig.VERSION_NAME}\n" +
                StringWriter().also { error.printStackTrace(PrintWriter(it)) }) }
            previous?.uncaughtException(thread, error)
        }
    }

    private const val SENT = "alarmeha_ultimo_erro.enviado"

    /** Se houver um erro fatal ainda não enviado, manda ao HA (evento app_erro) para diagnóstico. */
    fun reportPending(context: Context) {
        runCatching {
            val app = context.applicationContext
            val file = File(app.filesDir, FILE)
            if (!file.exists()) return
            val sent = File(app.filesDir, SENT)
            if (sent.exists() && sent.lastModified() >= file.lastModified()) return
            val config = HaConfig(app)
            if (!config.hasWebhook) return
            val body = org.json.JSONObject().apply {
                put("origem", "alarmeha")
                put("evento", "app_erro")
                put("versao", org.fossify.clock.BuildConfig.VERSION_NAME)
                put("quando", HaWebhook.now())
                put("erro", file.readText())
            }.toString().toByteArray()
            // Envio direto (sem WorkManager), para funcionar mesmo se o erro estiver na fila de envio.
            Thread {
                for (base in config.candidateUrls) {
                    val ok = runCatching {
                        val c = java.net.URL(config.webhookUrlFor(base)).openConnection() as java.net.HttpURLConnection
                        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(body) }
                        val code = c.responseCode; c.disconnect(); code in 200..299
                    }.getOrDefault(false)
                    if (ok) { runCatching { sent.writeText(HaWebhook.now()) }; break }
                }
            }.start()
        }
    }

    /** Copia o erro para Download/AlarmeHA-erro.txt (visível no app Arquivos, sem rede nem HA). */
    private fun writeToDownloads(context: Context, text: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "AlarmeHA-erro-${System.currentTimeMillis()}.txt")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    fun last(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
