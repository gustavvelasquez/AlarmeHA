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

/** Primeira abertura: convida a ativar pelo Home Assistant e lembra do despertador antigo. */
object HaOnboarding {
    private const val COMPANION_PLAY_URL = "https://play.google.com/store/apps/details?id=io.homeassistant.companion.android"

    fun maybeShow(activity: Activity) {
        val config = HaConfig(activity)
        if (config.isPaired || config.onboardingDismissed) return
        AlertDialog.Builder(activity)
            .setTitle("Bem-vindo ao AlarmeHA")
            .setMessage(
                "Ative este celular pelo Home Assistant da casa: o app Home Assistant abre o painel " +
                    "Despertador, onde você toca em Ativar.\n\n" + oldAlarmHint(activity)
            )
            .setPositiveButton("Ativar pelo Home Assistant") { _, _ -> openPanel(activity) }
            .setNegativeButton("Agora não") { _, _ -> config.onboardingDismissed = true }
            .setNeutralButton("Avançado") { _, _ ->
                activity.startActivity(Intent(activity, HaPairingActivity::class.java))
            }
            .show()
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

    fun openPanel(activity: Activity) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate/${HaActivation.PANEL_PATH}"))
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(activity)
                .setTitle("App Home Assistant não encontrado")
                .setMessage(
                    "Para ativar, instale o app Home Assistant (Companion), entre com a sua conta da casa " +
                        "e toque de novo em Ativar pelo Home Assistant.\n\nSem o Companion o HA não consegue " +
                        "enviar comandos a este celular."
                )
                .setPositiveButton("Abrir a Play Store") { _, _ ->
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COMPANION_PLAY_URL))) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
