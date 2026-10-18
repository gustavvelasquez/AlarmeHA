package org.fossify.clock.ha

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.clock.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Envia eventos ao webhook do Home Assistant, com fila em disco e novas tentativas (WorkManager). */
object HaWebhook {
    private const val OUTBOX = "ha_outbox"
    private const val KEY_FILE = "file"

    fun now(): String = isoDate(Date())

    fun isoDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(date)

    fun send(context: Context, event: String, fields: JSONObject = JSONObject()) {
        val config = HaConfig(context)
        if (!config.hasWebhook) return
        val payload = JSONObject().apply {
            put("origem", "alarmeha")
            put("evento", event)
            put("dispositivo", deviceName(context))
            if (config.personName.isNotEmpty()) put("pessoa", config.personName)
            put("versao", BuildConfig.VERSION_NAME)
            put("quando", now())
            fields.keys().forEach { put(it, fields.get(it)) }
        }

        val dir = File(context.filesDir, OUTBOX).apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
        file.writeText(payload.toString())

        val request = OneTimeWorkRequestBuilder<HaWebhookWorker>()
            .setInputData(workDataOf(KEY_FILE to file.absolutePath))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    class HaWebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val file = File(inputData.getString(KEY_FILE) ?: return@withContext Result.failure())
            if (!file.exists()) return@withContext Result.success()
            val config = HaConfig(applicationContext)
            if (!config.hasWebhook) {
                file.delete()
                return@withContext Result.success()
            }

            val ok = try {
                val connection = (URL(config.webhookUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.use { it.write(file.readBytes()) }
                val code = connection.responseCode
                connection.disconnect()
                code in 200..299
            } catch (e: Exception) {
                false
            }

            when {
                ok -> {
                    file.delete()
                    Result.success()
                }
                runAttemptCount >= MAX_ATTEMPTS -> {
                    file.delete()
                    Result.failure()
                }
                else -> Result.retry()
            }
        }
    }

    private const val MAX_ATTEMPTS = 8
}
