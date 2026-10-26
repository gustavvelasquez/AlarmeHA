package org.fossify.clock.ha

import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.appcompat.app.AlertDialog
import java.util.Date

/** Ativação obrigatória na primeira vez, abertura do painel e dica do despertador antigo. */
object HaOnboarding {
    /** Enquanto o app nunca foi ativado, troca a tela atual pela ativação obrigatória. Retorna true se trocou. */
    fun redirectIfNeverActivated(activity: Activity): Boolean {
        if (HaConfig(activity).everActivated) return false
        activity.startActivity(Intent(activity, HaActivationRequiredActivity::class.java))
        activity.finish()
        return true
    }

    /** Próximo alarme do sistema (de outro app), para o usuário recriar aqui e desligar o antigo. */
    fun oldAlarmHint(activity: Activity): String {
        val info = activity.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val creator = info?.showIntent?.creatorPackage
        val base = "Depois, desative os alarmes do relógio antigo para não tocarem dois despertadores."
        if (info == null || creator == activity.packageName) return base
        val time = Date(info.triggerTime)
        val when_ = "${DateFormat.getMediumDateFormat(activity).format(time)} ${DateFormat.getTimeFormat(activity).format(time)}"
        val app = creator?.let { appLabel(activity, it) }
        return "Seu próximo alarme do sistema é $when_${app?.let { " (no app $it)" } ?: ""}. " +
            "Recrie-o no AlarmeHA (ou pelo painel Despertador). $base"
    }

    private fun appLabel(activity: Activity, pkg: String): String? = try {
        val pm = activity.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    const val COMPANION_MISSING_TITLE = "App Home Assistant não encontrado"
    const val COMPANION_MISSING_TEXT =
        "O AlarmeHA precisa do app Home Assistant (Companion), que não está instalado neste celular. " +
            "É por ele que o HA envia os comandos a este celular e que a ativação abre o painel Despertador.\n\n" +
            "Instale-o pela Play Store, entre com a sua conta da casa e toque de novo em Ativar."

    fun showCompanionMissing(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(COMPANION_MISSING_TITLE)
            .setMessage(COMPANION_MISSING_TEXT)
            .setPositiveButton("Abrir a Play Store") { _, _ -> HaCompanion.openStore(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun openPanel(activity: Activity) {
        if (!HaCompanion.isInstalled(activity)) {
            showCompanionMissing(activity)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate/${HaActivation.PANEL_ACTIVATE_PATH}"))
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showCompanionMissing(activity)
        }
    }
}
