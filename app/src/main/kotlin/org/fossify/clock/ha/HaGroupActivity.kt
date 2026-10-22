package org.fossify.clock.ha

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.AlarmsAdapter
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getSelectedDaysString
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Tela do grupo (0.9.0, visual do Relógio da Samsung): nome grande, "Desativar tudo" / "Ativar tudo" e cada alarme
 * com a sua chave. Desativar = pular só aquele toque (cancela soneca em andamento); Ativar = religar.
 * Horário e nome não se editam aqui: vêm da agenda do HA.
 */
class HaGroupActivity : SimpleActivity() {
    private lateinit var column: LinearLayout
    private lateinit var group: String

    override fun onCreate(savedInstanceState: Bundle?) {
        HaCrashLog.install(this)
        super.onCreate(savedInstanceState)
        group = intent.getStringExtra(EXTRA_GROUP) ?: run { finish(); return }
        val padding = dp(16)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(HaStyle.BG)
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        EventBus.getDefault().register(this)
    }

    override fun onDestroy() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefresh(@Suppress("UNUSED_PARAMETER") event: AlarmEvent.Refresh) = load()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load() = ensureBackgroundThread {
        val infos = HaRefStore.get(this).allInfo().filter { it.group == group }
        val alarms = infos.mapNotNull { info -> dbHelper.getAlarmWithId(info.alarmId)?.let { info to it } }
        runOnUiThread { if (!isFinishing) render(alarms) }
    }

    private fun item(info: HaRefStore.Info, a: Alarm) =
        HaListOrder.Item(a.id, a.timeInMinutes, a.days, a.isEnabled, info.group, info.skippedAt)

    private fun render(members: List<Pair<HaRefStore.Info, Alarm>>) {
        column.removeAllViews()
        if (members.isEmpty()) {
            finish()
            return
        }
        val now = System.currentTimeMillis()
        val items = members.map { (info, a) -> item(info, a) }
        val groups = setOf(group)
        val order = HaListOrder.order(items, groups, now)
        val byId = members.associateBy { it.second.id }

        // Título grande centralizado (Samsung), só o nome do tipo.
        TextView(this).apply {
            text = HaUi.baseName(members.firstNotNullOfOrNull { it.first.groupName }, group)
            textSize = 30f
            typeface = HaStyle.boldTime()
            gravity = Gravity.CENTER
            setTextColor(HaStyle.TEXT)
            setPadding(dp(24), dp(32), dp(24), 0)
        }.also { column.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }

        // Ativar tudo / Desativar tudo: só o que faz sentido; os dois quando misto.
        val (canOff, canOn) = HaListOrder.cardButtons(items, now)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: Int, on: Boolean) = TextView(this).apply {
            setText(label)
            textSize = 15f
            typeface = HaStyle.medium()
            setTextColor(HaStyle.TEXT)
            background = HaStyle.rounded(this@HaGroupActivity, HaStyle.BUTTON, 22f)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            isClickable = true
            setOnClickListener { HaSkip.setGroup(this@HaGroupActivity, group, on) }
        }.also { buttons.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(8) }) }
        if (canOn) button(R.string.ha_group_all_on, on = true)
        if (canOff) button(R.string.ha_group_all_off, on = false)
        column.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(24) })

        // Barra ‹ … ⋮ (grupo do HA: sem + nem editar/renomear; o ⋮ só leva às configurações).
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun icon(res: Int, desc: Int, click: (View) -> Unit) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(HaStyle.TEXT)
            contentDescription = getString(desc)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener(click)
        }
        bar.addView(icon(org.fossify.commons.R.drawable.ic_chevron_left_vector, R.string.ha_back) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(icon(org.fossify.commons.R.drawable.ic_three_dots_vector, R.string.ha_more_options) { v ->
            android.widget.PopupMenu(this, v).apply {
                menu.add(org.fossify.commons.R.string.settings)
                setOnMenuItemClickListener { startActivity(Intent(this@HaGroupActivity, org.fossify.clock.activities.SettingsActivity::class.java)); true }
            }.show()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16); bottomMargin = dp(4) })

        // Cartão com os alarmes do grupo, rótulo acima da hora, divisórias.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = HaStyle.rounded(this@HaGroupActivity, HaStyle.CARD, 26f)
        }
        // 0.9.2: vagas com o mesmo horário e rótulo (ex.: qua e qui) viram uma linha com os dias.
        val ordered = order.mapNotNull { byId[it] }
        val lines = HaUi.lines(ordered.map { (info, a) ->
            val it = item(info, a)
            val ended = HaListOrder.ended(it, groups, now) != null
            HaUi.Member(a.id, a.timeInMinutes, a.label, a.days, a.isEnabled && !HaSkipLogic.isSkipActive(info.skippedAt, now), ended,
                HaSkipLogic.skipTarget(a.timeInMinutes, a.days, now) ?: HaSkipLogic.nextOccurrence(a.timeInMinutes, a.days, now))
        })
        lines.forEachIndexed { i, line ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(HaStyle.DIVIDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(20); marginEnd = dp(20) })
            val (info, alarm) = byId.getValue(line.ids.first())
            card.addView(if (line.states == null) row(info, alarm, now) else mergedRow(line, alarm))
        }
        column.addView(card)
    }

    /** Linha de várias vagas (mesmo horário e rótulo): dias marcados; a chave age só na próxima ocorrência. */
    private fun mergedRow(line: HaUi.Line, first: Alarm): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), dp(18), dp(14), dp(18))
        }
        val color = if (line.faded) HaStyle.DIM else HaStyle.TEXT
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (first.label.isNotEmpty()) {
            TextView(this).apply { text = first.label; textSize = 15f; setTextColor(color); maxLines = 1 }.also { texts.addView(it) }
        }
        TextView(this).apply {
            text = HaUi.hhmm(first.timeInMinutes)
            textSize = 44f
            typeface = HaStyle.boldTime()
            includeFontPadding = false
            setTextColor(color)
        }.also { texts.addView(it) }
        HaDaysView(this).apply { setStates(line.states!!); faded = line.faded }
            .also { texts.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }) }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = line.checked
            HaStyle.paintSwitch(this)
            isEnabled = line.target != null
            alpha = if (line.target != null) 1f else 0.5f
            setOnClickListener { _ -> line.target?.let { id -> HaSkip.toggle(this@HaGroupActivity, id, isChecked) } }
        }.also { row.addView(it) }
        return row
    }

    private fun row(info: HaRefStore.Info, alarm: Alarm, now: Long): View {
        val it = item(info, alarm)
        val skipping = HaSkipLogic.isSkipActive(info.skippedAt, now)
        val ended = HaListOrder.ended(it, setOf(group), now)
        val on = alarm.isEnabled && !skipping
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), dp(18), dp(14), dp(18))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (alarm.label.isNotEmpty()) {
            TextView(this).apply { text = alarm.label; textSize = 15f; setTextColor(if (on) HaStyle.TEXT else HaStyle.DIM); maxLines = 1 }
                .also { labelRow.addView(it) }
        }
        if (skipping || ended == HaListOrder.Ended.SKIPPED) {
            TextView(this).apply {
                setText(R.string.ha_skipped_past)
                textSize = 12f
                typeface = HaStyle.medium()
                setTextColor(HaStyle.TEXT_2)
                background = HaStyle.rounded(this@HaGroupActivity, 0xFF2C2C30.toInt(), 9f)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }.also { labelRow.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
        }
        if (labelRow.childCount > 0) texts.addView(labelRow)
        TextView(this).apply {
            text = HaUi.hhmm(alarm.timeInMinutes)
            textSize = 44f
            typeface = HaStyle.boldTime()
            includeFontPadding = false
            setTextColor(if (on) HaStyle.TEXT else HaStyle.DIM)
        }.also { texts.addView(it) }
        val recurring = alarm.days > 0 && !skipping && ended == null
        if (recurring) {
            HaDaysView(this).apply { setDays(alarm.days); faded = !on }
                .also { texts.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }) }
        } else {
            TextView(this).apply {
                text = when {
                    ended == HaListOrder.Ended.SKIPPED -> getString(R.string.ha_skipped_past)
                    ended == HaListOrder.Ended.RUNG -> getString(R.string.ha_rung_at, HaUi.hhmm(alarm.timeInMinutes))
                    skipping -> HaUi.skippedLine(it, now)
                    else -> HaListOrder.longDate(HaSkipLogic.nextOccurrence(alarm.timeInMinutes, alarm.days, now) ?: now)
                }
                textSize = 14f
                typeface = HaStyle.boldTime()
                setTextColor(if (on) HaStyle.ACCENT else HaStyle.DIM)
            }.also { texts.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }) }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = on
            HaStyle.paintSwitch(this)
            // Já tocou (o HA manteve no grupo desligado): não volta a tocar.
            isEnabled = ended == null
            alpha = if (ended == null) 1f else 0.5f
            setOnClickListener { _ -> HaSkip.toggle(this@HaGroupActivity, alarm.id, isChecked) }
        }.also { row.addView(it) }
        return row
    }

    companion object {
        private const val EXTRA_GROUP = "grupo"
        fun intent(context: Context, group: String) = Intent(context, HaGroupActivity::class.java).putExtra(EXTRA_GROUP, group)
    }
}
