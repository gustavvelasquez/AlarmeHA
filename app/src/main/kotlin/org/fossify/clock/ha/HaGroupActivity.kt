package org.fossify.clock.ha

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
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
 * Tela do grupo (compromisso do HA): nome grande, "Desativar tudo" / "Ativar tudo" e cada alarme
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
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(getProperBackgroundColor())
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
        val textColor = getProperTextColor()
        val primary = getProperPrimaryColor()
        val items = members.map { (info, a) -> item(info, a) }
        val groups = setOf(group)
        val order = HaListOrder.order(items, groups, now)
        val byId = members.associateBy { it.second.id }

        TextView(this).apply {
            text = members.first().first.groupName ?: group
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, dp(24), 0, dp(4))
        }.also { column.addView(it) }
        val sum = HaListOrder.summary(items, now)
        TextView(this).apply {
            text = (sum.nextRing?.let { "Próximo: " + HaListOrder.longDate(it) + " · " } ?: "") +
                getString(R.string.ha_card_count, sum.on, sum.off)
            textSize = 15f
            setTextColor(textColor)
        }.also { column.addView(it) }

        val (canOff, canOn) = HaListOrder.cardButtons(items, now)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12)) }
        fun button(label: Int, on: Boolean) = Button(this).apply {
            setText(label)
            isAllCaps = false
            setTextColor(primary)
            setOnClickListener { HaSkip.setGroup(this@HaGroupActivity, group, on) }
        }.also { buttons.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        if (canOff) button(R.string.ha_group_all_off, on = false)
        if (canOn) button(R.string.ha_group_all_on, on = true)
        if (canOff || canOn) column.addView(buttons)

        order.mapNotNull { byId[it] }.forEach { (info, alarm) -> column.addView(row(info, alarm, now, textColor, primary)) }
    }

    private fun row(info: HaRefStore.Info, alarm: Alarm, now: Long, textColor: Int, primary: Int): View {
        val it = item(info, alarm)
        val skipping = HaSkipLogic.isSkipActive(info.skippedAt, now)
        val ended = HaListOrder.ended(it, setOf(group), now)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        TextView(this).apply {
            text = getFormattedTime(alarm.timeInMinutes * 60, false, true)
            textSize = 32f
            setTextColor(textColor)
        }.also { texts.addView(it) }
        TextView(this).apply {
            text = when (ended) {
                HaListOrder.Ended.SKIPPED -> getString(R.string.ha_skipped_past)
                HaListOrder.Ended.RUNG -> getString(R.string.ha_rung_at, getFormattedTime(alarm.timeInMinutes * 60, false, false))
                null -> if (skipping) "Pula " + HaListOrder.longDate(info.skippedAt!!) else
                    HaListOrder.dayText(it, if (alarm.days > 0) getSelectedDaysString(alarm.days) else null, now)
            }
            textSize = 15f
            setTextColor(textColor)
        }.also { texts.addView(it) }
        if (alarm.label.isNotEmpty()) {
            TextView(this).apply { text = alarm.label; textSize = 14f; setTextColor(textColor) }.also { texts.addView(it) }
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val on = alarm.isEnabled && !skipping
        SwitchCompat(this).apply {
            isChecked = on
            // Já tocou (o HA manteve no grupo desligado): não volta a tocar.
            isEnabled = ended == null
            setOnClickListener { _ -> HaSkip.toggle(this@HaGroupActivity, alarm.id, isChecked) }
        }.also { row.addView(it) }
        row.alpha = if (on) 1f else AlarmsAdapter.FADE_OFF
        return row
    }

    companion object {
        private const val EXTRA_GROUP = "grupo"
        fun intent(context: Context, group: String) = Intent(context, HaGroupActivity::class.java).putExtra(EXTRA_GROUP, group)
    }
}
