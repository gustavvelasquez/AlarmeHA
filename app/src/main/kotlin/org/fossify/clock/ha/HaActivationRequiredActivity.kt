package org.fossify.clock.ha

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

/** Tela obrigatória enquanto o app nunca foi ativado: sem ela não há acesso aos alarmes.
 * Depois da primeira ativação ela nunca mais aparece (nem sem internet, HA ou token válido). */
class HaActivationRequiredActivity : SimpleActivity() {
    private var companionWarning: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        HaCrashLog.install(this)
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })
    }

    override fun onResume() {
        super.onResume()
        if (HaConfig(this).everActivated) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // Reconfere ao voltar (por exemplo, da Play Store depois de instalar o Companion).
        companionWarning?.visibility = if (HaCompanion.isInstalled(this)) View.GONE else View.VISIBLE
    }

    private fun buildLayout(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val textColor = getProperTextColor()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding * 2, padding, padding)
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, padding / 3, 0, padding / 3)
        }.also { column.addView(it) }

        text("Ative o AlarmeHA", 26f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getProperPrimaryColor())
        }
        text(
            "O AlarmeHA é o despertador da casa, controlado pelo Home Assistant. " +
                "Antes de usar, ative este celular: o app Home Assistant abre o painel Despertador " +
                "e você toca em Confirmar ativação.\n\nDepois de ativado, os alarmes funcionam " +
                "normalmente mesmo sem internet.",
            16f
        )
        companionWarning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding / 2, padding / 2, padding / 2, padding / 2)
            setBackgroundColor(0x33F44336)
            addView(TextView(this@HaActivationRequiredActivity).apply {
                text = HaOnboarding.COMPANION_MISSING_TITLE
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@HaActivationRequiredActivity).apply {
                text = HaOnboarding.COMPANION_MISSING_TEXT
                textSize = 15f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(0, padding / 3, 0, padding / 3)
            })
            addView(Button(this@HaActivationRequiredActivity).apply {
                text = "Abrir a Play Store"
                isAllCaps = false
                setOnClickListener { HaCompanion.openStore(this@HaActivationRequiredActivity) }
            })
            visibility = if (HaCompanion.isInstalled(this@HaActivationRequiredActivity)) View.GONE else View.VISIBLE
        }.also { column.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = padding / 2 }) }
        column.addView(Button(this).apply {
            text = "Ativar"
            textSize = 20f
            isAllCaps = false
            setOnClickListener { HaOnboarding.openPanel(this@HaActivationRequiredActivity) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * density).toInt()).apply {
            topMargin = padding
        })
        text("Não abriu? Ativar manualmente", 14f).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setTextColor(getProperPrimaryColor())
            setPadding(0, padding, 0, padding)
            setOnClickListener {
                startActivity(Intent(this@HaActivationRequiredActivity, HaPairingActivity::class.java))
            }
        }

        return ScrollView(this).apply {
            setBackgroundColor(getProperBackgroundColor())
            addView(column)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }
}
