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
            previous?.uncaughtException(thread, error)
        }
    }

    fun last(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
