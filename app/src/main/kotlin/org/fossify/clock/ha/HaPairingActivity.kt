package org.fossify.clock.ha

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONObject

/** Tela do Home Assistant: ativar pelo painel Despertador (caminho principal), receber o link
 * alarmeha://ativar e, no modo avançado, ler QR / colar configuração. */
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
        handleActivationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleActivationIntent(intent)
    }

    private fun handleActivationIntent(intent: Intent?) {
        val request = HaActivation.parse(intent?.data) ?: return
        intent?.data = null
        confirmActivation(request)
    }

    private fun confirmActivation(request: HaActivation.Request) {
        AlertDialog.Builder(this)
            .setTitle("Ativar com o HA da casa?")
            .setMessage(
                "Este celular passará a receber comandos de alarme de:\n${request.url}" +
                    (if (config.isPaired) "\n\nA ativação atual será substituída." else "")
            )
            .setPositiveButton("Ativar") { _, _ -> activate(request) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun activate(request: HaActivation.Request) {
        status.text = "Ativando…"
        ensureBackgroundThread {
            val error = HaActivation.redeem(this, request)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                shownToken = null
                refresh()
                if (error == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Celular ativado")
                        .setMessage("Pronto! Gerencie os alarmes pelo painel Despertador.\n\n" + HaOnboarding.oldAlarmHint(this))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Não foi possível ativar")
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
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
            "Abra o painel Despertador no app Home Assistant e toque em Ativar. " +
                "O botão abaixo abre o painel direto.",
            14f
        )
        button("Ativar pelo Home Assistant") { HaOnboarding.openPanel(this) }
        button("Testar: enviar lista de alarmes ao HA") {
            if (config.hasWebhook) {
                HaEvents.sendList(this, "teste")
                toast("Lista enviada (pedido \"teste\")")
            } else {
                toast("Ative o celular primeiro")
            }
        }
        button("Desativar") { confirmUnpair() }

        text("Avançado", 18f, bold = true).setTextColor(getProperPrimaryColor())
        text(
            "Se o link do painel não abrir este app: leia o QR code ou copie o texto de pareamento do painel " +
                "e use Colar configuração. Configurações manuais antigas (v1) geram um token para colar no HA.",
            14f
        )
        button("Ler QR code") { startScan() }
        button("Colar configuração") { pasteConfig() }
        tokenView = text(size = 15f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            movementMethod = LinkMovementMethod.getInstance()
        }
        copyToken = button("Copiar token") { shownToken?.let { copy(it) } }
        button("Gerar novo token (configuração manual)") { confirmRegenerate() }

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
            append(if (config.isPaired) "Status: ativado" else "Status: não ativado")
            if (config.hasWebhook) {
                append("\nHA: ${config.haUrl}")
                if (config.personName.isNotEmpty()) append("\nPessoa: ${config.personName}")
            }
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
        val v2 = data?.let { HaActivation.parse(it) }
        if (v2 != null) {
            confirmActivation(v2)
            return
        }
        val url = data?.optString("url").orEmpty().trim().trimEnd('/')
        val webhook = data?.optString("webhook").orEmpty().trim()
        if (!url.matches(Regex("^https?://[^\\s/]+(/[^\\s]*)?$")) || !webhook.matches(Regex("^[A-Za-z0-9_-]{8,128}$"))) {
            toast("Configuração inválida: copie o texto de pareamento do painel Despertador")
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
            .setMessage("Gerar um novo token? Só para configuração manual: o token atual deixa de funcionar. Se você ativou pelo painel, prefira ativar de novo.")
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
            .setMessage("Desativar o Home Assistant neste celular? O app apaga a URL, o webhook e o token. Os alarmes continuam no relógio.")
            .setPositiveButton("Desativar") { _, _ ->
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
