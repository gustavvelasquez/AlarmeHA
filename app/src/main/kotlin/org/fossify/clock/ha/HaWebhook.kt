package org.fossify.clock.ha

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.clock.BuildConfig
import org.fossify.clock.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Envia eventos ao webhook do Home Assistant.
 * Cada evento vai para uma fila em disco e o envio começa NA HORA (thread de fundo); o WorkManager
 * (expedited, com rede) é só a reserva para quando não deu (sem rede, HA fora, processo morto).
 * Regras da fila em [HaOutboxLogic]: ordem de criação, 24 h, "lista" colapsa, webhook antigo sai.
 */
object HaWebhook {
    private const val OUTBOX = "ha_outbox"
    private const val WORK_NAME = "alarmeha_outbox"
    private const val CHANNEL = "alarmeha_envio"
    private val lock = Any()
    private val seq = AtomicInteger(0)

    fun now(): String = isoDate(Date())

    fun isoDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(date)

    private fun dir(context: Context) = File(context.applicationContext.filesDir, OUTBOX)

    fun pending(context: Context): Int = dir(context).listFiles()?.count { it.name.endsWith(".json") } ?: 0

    fun send(context: Context, event: String, fields: JSONObject = JSONObject()) {
        val app = context.applicationContext
        val config = HaConfig(app)
        if (!config.hasWebhook) return
        val payload = JSONObject().apply {
            put("origem", "alarmeha")
            put("evento", event)
            put("dispositivo", deviceName(app))
            if (config.personName.isNotEmpty()) put("pessoa", config.personName)
            put("versao", BuildConfig.VERSION_NAME)
            put("quando", now())
            fields.keys().forEach { put(it, fields.get(it)) }
        }
        synchronized(lock) {
            val dir = dir(app).apply { mkdirs() }
            val names = dir.list()?.toList().orEmpty()
            HaOutboxLogic.superseded(names, event).forEach { File(dir, it).delete() }
            val name = HaOutboxLogic.fileName(System.currentTimeMillis(), seq.incrementAndGet(), event, HaOutboxLogic.hash(config.webhookId))
            File(dir, name).writeText(payload.toString())
        }
        // Já: tenta em seguida; a reserva fica agendada caso o envio imediato não consiga.
        Thread({ runCatching { flush(app) } }, "alarmeha-envio").start()
        schedule(app)
    }

    /** Agenda a reserva (uma só: substitui a anterior, para não esperar um backoff antigo). */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<HaWebhookWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request) }
    }

    /** Pareado de novo / desativado: o que estava na fila era do webhook antigo. */
    fun clear(context: Context) = synchronized(lock) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Envia a fila em ordem. true = fila vazia no fim. */
    fun flush(context: Context): Boolean = synchronized(lock) {
        val config = HaConfig(context)
        val dir = dir(context)
        if (!config.hasWebhook) {
            dir.listFiles()?.forEach { it.delete() }
            return true
        }
        val names = dir.list()?.filter { it.endsWith(".json") }.orEmpty()
        val plan = HaOutboxLogic.plan(names, System.currentTimeMillis(), HaOutboxLogic.hash(config.webhookId))
        if (plan.drop.isNotEmpty()) {
            plan.drop.forEach { File(dir, it).delete() }
            config.droppedEvents += plan.drop.size
        }
        for (name in plan.send) {
            val file = File(dir, name)
            if (!file.exists()) continue
            val body = file.readBytes()
            // Como o Companion: a última URL que funcionou primeiro, depois as outras.
            var worked: String? = null
            val errors = ArrayList<String>()
            for (base in config.candidateUrls) {
                val error = post(config.webhookUrlFor(base), body)
                if (error == null) {
                    worked = base
                    break
                }
                errors.add("$base: $error")
            }
            if (worked == null) {
                config.lastError = errors.joinToString("\n").ifEmpty { "sem URL do HA" }
                config.lastErrorAt = System.currentTimeMillis()
                return false
            }
            if (worked != config.haUrl) config.haUrl = worked
            file.delete()
            config.lastOkAt = System.currentTimeMillis()
            config.lastOkUrl = worked
        }
        true
    }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /** null = deu certo; senão o motivo (HTTP xxx ou a exceção). */
    private fun post(url: String, body: ByteArray): String? =
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..299) null else "HTTP $code"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }

    class HaWebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            if (flush(applicationContext)) Result.success() else Result.retry()
        }

        /** Android 8–11 roda o "expedited" como serviço em primeiro plano: precisa de notificação. */
        override suspend fun getForegroundInfo(): ForegroundInfo {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Envio ao Home Assistant", NotificationManager.IMPORTANCE_MIN))
            }
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_alarm_vector)
                .setContentTitle("Enviando ao Home Assistant")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            // Sem tipo: só é usado no Android 8–11 (no 12+ o expedited é um job comum).
            return ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private const val NOTIFICATION_ID = 7341
}
