package org.fossify.clock.ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Recebe comandos do Home Assistant enviados pelo app Companion (command_broadcast_intent).
 * Exportado de propósito: a proteção é o token, conferido pelo hash em tempo constante.
 */
class HaCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMANDO) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val extras = intent.extras
        ensureBackgroundThread {
            try {
                HaCommandProcessor(appContext).process(extras)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_COMANDO = "br.casa.alarmeha.action.COMANDO"
    }
}
