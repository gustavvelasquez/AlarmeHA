package org.fossify.clock.ha

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.fossify.clock.activities.SimpleActivity
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.json.JSONObject

/** Tela de pareamento com o Home Assistant: ler QR, gerar/copiar token, testar e desparear. */
class HaPairingActivity : SimpleActivity() {
    private lateinit var config: HaConfig
    private lateinit var status: TextView
    private lateinit var tokenView: TextView
    private lateinit var copyToken: Button
    private var shownToken: String? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { applyPairingData(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = HaConfig(this)
        setContentView(buildLayout())
        refresh()
    }

    private fun buildLayout(): View {
        val textColor = getProperTextColor()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun text(value: String = "", size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(textColor)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, padding / 2, 0, padding / 2)
        }.also { column.addView(it) }

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also { column.addView(it) }

        text("Home Assistant", 22f, bold = true).setTextColor(getProperPrimaryColor())
        status = text()
        text(
            "1. No Home Assistant, mostre o QR code de pareamento (veja docs/integracao-home-assistant.md).\n" +
                "2. Toque em \"Ler QR code\".\n" +
                "3. Copie o token e cole no secrets.yaml do Home Assistant.",
            14f
        )
        button("Ler QR code do Home Assistant") { startScan() }
        button("Colar configuração (texto do QR)") { pasteConfig() }
        tokenView = text(size = 15f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            movementMethod = LinkMovementMethod.getInstance()
        }
        copyToken = button("Copiar token") { shownToken?.let { copy(it) } }
        button("Gerar novo token") { confirmRegenerate() }
        button("Testar: enviar lista de alarmes ao HA") {
            if (config.hasWebhook) {
                HaEvents.sendList(this, "teste")
                toast("Lista enviada (pedido \"teste\")")
            } else {
                toast("Leia o QR code primeiro")
            }
        }
        button("Desparear") { confirmUnpair() }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(getProperBackgroundColor())
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }

    private fun refresh() {
        status.text = buildString {
            append(if (config.isPaired) "Status: pareado" else "Status: não pareado")
            if (config.hasWebhook) {
                append("\nHA: ${config.haUrl}")
                if (config.personName.isNotEmpty()) append("\nPessoa: ${config.personName}")
            }
            append("\nToken: ").append(if (config.hasToken) "definido" else "não gerado")
            if (config.rejectedCommands > 0) append("\nComandos recusados (token errado): ${config.rejectedCommands}")
        }
        val token = shownToken
        tokenView.text = if (token != null) {
            "Token (mostrado só agora; copie e cole no HA):\n$token"
        } else {
            ""
        }
        tokenView.visibility = if (token != null) View.VISIBLE else View.GONE
        copyToken.visibility = if (token != null) View.VISIBLE else View.GONE
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Aponte para o QR code do Home Assistant")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun pasteConfig() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast("Área de transferência vazia")
        } else {
            applyPairingData(text)
        }
    }

    private fun applyPairingData(raw: String) {
        val data = runCatching { JSONObject(raw.trim()) }.getOrNull()
        val url = data?.optString("url").orEmpty().trim().trimEnd('/')
        val webhook = data?.optString("webhook").orEmpty().trim()
        if (!url.matches(Regex("^https?://[^\\s/]+(/[^\\s]*)?$")) || !webhook.matches(Regex("^[A-Za-z0-9_-]{8,128}$"))) {
            toast("QR code inválido: esperado {\"v\":1,\"url\":...,\"webhook\":...}")
            return
        }

        config.haUrl = url
        config.webhookId = webhook
        config.personName = data?.optString("nome").orEmpty().trim().take(40)
        if (!config.hasToken) {
            shownToken = config.generateToken()
        }
        sendPaired()
        toast("Configuração do Home Assistant salva")
        refresh()
    }

    private fun sendPaired() {
        HaWebhook.send(this, "pareado", JSONObject().apply {
            put("pedido", JSONObject.NULL)
            put("token_definido", config.hasToken)
        })
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setMessage("Gerar um novo token? O token antigo deixa de funcionar e você terá de atualizar o secrets.yaml do Home Assistant.")
            .setPositiveButton("Gerar") { _, _ ->
                shownToken = config.generateToken()
                if (config.hasWebhook) sendPaired()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setMessage("Desparear do Home Assistant? O app apaga a URL, o webhook e o token. Os alarmes continuam no relógio.")
            .setPositiveButton("Desparear") { _, _ ->
                config.unpair()
                shownToken = null
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copy(value: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Token AlarmeHA", value))
        toast("Token copiado")
    }
}
