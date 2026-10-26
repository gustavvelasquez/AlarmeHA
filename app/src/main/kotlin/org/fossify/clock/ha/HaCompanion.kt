package org.fossify.clock.ha

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** O app Home Assistant (Companion) é obrigatório: é por ele que o HA manda os comandos e que a ativação abre o painel. */
object HaCompanion {
    /** Versão completa (Play Store) e "minimal" (F-Droid/sem Google). Também declaradas em <queries> no manifest. */
    val PACKAGES = listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")
    const val PLAY_MARKET = "market://details?id=io.homeassistant.companion.android"
    const val PLAY_WEB = "https://play.google.com/store/apps/details?id=io.homeassistant.companion.android"

    /** Regra pura: algum dos pacotes do Companion está instalado? (testada em src/test) */
    fun anyInstalled(isInstalled: (String) -> Boolean): Boolean = PACKAGES.any(isInstalled)

    /** Ordem de tentativa para abrir a loja: app da Play Store, depois o navegador. */
    fun storeUris(): List<String> = listOf(PLAY_MARKET, PLAY_WEB)

    fun isInstalled(context: Context): Boolean = anyInstalled { pkg ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun openStore(activity: Activity) {
        for (uri in storeUris()) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                return
            } catch (e: ActivityNotFoundException) {
                // tenta o próximo
            }
        }
    }
}
