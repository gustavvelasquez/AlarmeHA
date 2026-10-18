package org.fossify.clock.ha

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.activities.SimpleActivity
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor

/** Permissões sem as quais o alarme não toca direito (ex.: sem tela cheia não aparece soneca/desligar). */
object HaPermissions {
    class Item(val title: String, val why: String, val granted: (Context) -> Boolean, val open: (Activity) -> Unit)

    private fun settings(activity: Activity, action: String, withPackage: Boolean = true) {
        val intent = Intent(action).apply {
            if (withPackage) data = Uri.parse("package:${activity.packageName}")
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
    }

    @SuppressLint("BatteryLife")
    fun items(): List<Item> {
        val list = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 33) {
            list += Item(
                "Notificações", "Para mostrar o alarme tocando e os botões de soneca e desligar.",
                { it.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                { a ->
                    val prefs = a.getSharedPreferences("alarmeha_perm", Context.MODE_PRIVATE)
                    if (!prefs.getBoolean("notif_pedida", false)) {
                        prefs.edit().putBoolean("notif_pedida", true).apply()
                        a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
                    } else {
                        a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
                    }
                }
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            list += Item(
                "Tela cheia", "Para a tela do alarme abrir por cima de tudo, até com o celular bloqueado.",
                { (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent() },
                { settings(it, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) }
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            list += Item(
                "Alarmes no horário exato", "Para o alarme tocar no minuto certo.",
                { (it.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() },
                { settings(it, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
            )
        }
        list += Item(
            "Sem restrição de bateria", "Para o Android não pausar o app e o alarme não falhar.",
            { (it.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(it.packageName) },
            { settings(it, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }
        )
        return list
    }

    fun allGranted(context: Context) = items().all { it.granted(context) }

    fun redirectIfMissing(activity: Activity): Boolean {
        if (allGranted(activity)) return false
        activity.startActivity(Intent(activity, HaPermissionsActivity::class.java))
        activity.finish()
        return true
    }
}

/** Tela obrigatória depois da ativação: um botão por permissão, ✓ quando liberada; só libera o app com tudo pronto. */
class HaPermissionsActivity : SimpleActivity() {
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        HaCrashLog.install(this)
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getProperBackgroundColor())
            addView(column)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })
    }

    override fun onResume() {
        super.onResume()
        if (HaPermissions.allGranted(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }

    private fun render() {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val textColor = getProperTextColor()
        column.removeAllViews()
        fun text(value: String, size: Float, bold: Boolean = false, color: Int = textColor) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, padding / 4, 0, padding / 4)
        }.also { column.addView(it) }

        text("Falta pouco", 26f, true, getProperPrimaryColor()).gravity = Gravity.CENTER
        text("Para o despertador funcionar direito, libere as permissões abaixo. Toque em cada uma e ative na tela que abrir.", 16f)
        for (item in HaPermissions.items()) {
            val ok = item.granted(this)
            text(if (ok) "✓ ${item.title}" else item.title, 18f, true, if (ok) getProperPrimaryColor() else textColor)
                .setPadding(0, padding, 0, 0)
            text(item.why, 14f)
            if (!ok) {
                column.addView(Button(this).apply {
                    text = "Permitir"
                    isAllCaps = false
                    textSize = 16f
                    setOnClickListener { item.open(this@HaPermissionsActivity) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * density).toInt()))
            }
        }
    }
}
