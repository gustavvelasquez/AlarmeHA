package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.databinding.ItemAlarmBinding
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.helpers.updateNonRecurringAlarmDay
import org.fossify.clock.interfaces.ToggleAlarmInterface
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getSelectedDaysString
import org.fossify.commons.extensions.move
import org.fossify.commons.helpers.EVERY_DAY_BIT
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.interfaces.ItemMoveCallback
import org.fossify.commons.interfaces.ItemTouchHelperContract
import org.fossify.commons.interfaces.StartReorderDragListener
import org.fossify.commons.views.MyRecyclerView
import org.greenrobot.eventbus.EventBus

class AlarmsAdapter(
    activity: SimpleActivity,
    private var alarms: ArrayList<Alarm>,
    private val toggleAlarmInterface: ToggleAlarmInterface,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), ItemTouchHelperContract {

    private var startReorderDragListener: StartReorderDragListener

    /** AlarmeHA: alarmId -> ref/grupo/pulo dos alarmes da agenda (manuais não entram). */
    private var haInfo: Map<Int, org.fossify.clock.ha.HaRefStore.Info> = emptyMap()
    private var haGroups: Map<String, List<Int>> = emptyMap()
    /** Todos os alarmes (a lista mostra só um cartão por grupo; o cartão resume os membros). */
    private var allAlarms: List<Alarm> = emptyList()

    fun updateHaInfo(info: Map<Int, org.fossify.clock.ha.HaRefStore.Info>, all: List<Alarm> = alarms) {
        haInfo = info
        allAlarms = all
        haGroups = org.fossify.clock.ha.HaSkipLogic.groupsOf(info.mapValues { it.value.group })
    }

    /** Grupo (cartão) do alarme, ou null para alarme solto. */
    fun haGroupOf(alarm: Alarm): String? = haInfo[alarm.id]?.group?.takeIf { haGroups.containsKey(it) }

    fun isHaAlarm(alarm: Alarm): Boolean = haInfo.containsKey(alarm.id)

    init {
        setupDragListener(true)
        val touchHelper = ItemTouchHelper(ItemMoveCallback(this))
        touchHelper.attachToRecyclerView(recyclerView)

        startReorderDragListener = object : StartReorderDragListener {
            override fun requestDrag(viewHolder: RecyclerView.ViewHolder) {
                touchHelper.startDrag(viewHolder)
            }
        }
    }

    override fun getActionMenuId() = R.menu.cab_alarms

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_delete -> deleteItems()
        }
    }

    override fun getSelectableItemCount() = alarms.size

    // Alarmes do HA não entram na seleção (apagar/arrastar): quem manda é a agenda.
    override fun getIsItemSelectable(position: Int) = alarms.getOrNull(position)?.let { !isHaAlarm(it) } ?: false

    override fun getItemSelectionKey(position: Int) = alarms.getOrNull(position)?.id

    override fun getItemKeyPosition(key: Int) = alarms.indexOfFirst { it.id == key }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeCreated() {
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onActionModeDestroyed() {
        notifyDataSetChanged()
    }

    override fun onRowClear(myViewHolder: ViewHolder?) {}

    override fun onRowSelected(myViewHolder: ViewHolder?) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemAlarmBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val alarm = alarms[position]
        holder.bindView(
            any = alarm,
            allowSingleClick = true,
            allowLongClick = true
        ) { itemView, _ ->
            setupView(itemView, alarm, holder)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = alarms.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: ArrayList<Alarm>) {
        alarms = newItems
        notifyDataSetChanged()
        finishActMode()
    }

    private fun deleteItems() {
        val alarmsToRemove = ArrayList<Alarm>()
        val positions = getSelectedItemPositions()
        getSelectedItems().forEach {
            alarmsToRemove.add(it)
        }

        alarms.removeAll(alarmsToRemove)
        removeSelectedItems(positions)
        activity.dbHelper.deleteAlarms(alarmsToRemove)
        EventBus.getDefault().post(AlarmEvent.Refresh)
    }

    private fun getSelectedItems(): ArrayList<Alarm> {
        return alarms.filter { selectedKeys.contains(it.id) } as ArrayList<Alarm>
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupView(view: View, alarm: Alarm, holder: ViewHolder) {
        val isSelected = selectedKeys.contains(alarm.id)
        ItemAlarmBinding.bind(view).apply {
            alarmHolder.isSelected = isSelected
            alarmDragHandle.beVisibleIf(selectedKeys.isNotEmpty())
            alarmDragHandle.applyColorFilter(textColor)
            alarmDragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    startReorderDragListener.requestDrag(holder)
                }
                false
            }
            val info = haInfo[alarm.id]
            val group = haGroupOf(alarm)
            val now = System.currentTimeMillis()
            alarmGroupHeader.beVisibleIf(false)
            styleCard(alarmHolder, group != null)
            if (group != null) {
                // Cartão do compromisso: nome, próximo toque e quantos ligados/desligados. Toque abre o grupo.
                val members = allAlarms.filter { haInfo[it.id]?.group == group }.map { haItem(it) }
                val sum = org.fossify.clock.ha.HaListOrder.summary(members, now)
                val shown = sum.nextRing?.let { java.util.Calendar.getInstance().apply { timeInMillis = it } }
                val minutes = shown?.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) } ?: alarm.timeInMinutes
                alarmTime.text = activity.getFormattedTime(passedSeconds = minutes * 60, showSeconds = false, makeAmPmSmaller = true)
                alarmTime.setTextColor(textColor)
                val countText = activity.getString(R.string.ha_card_count, sum.on, sum.off)
                alarmDays.text = (sum.nextRing?.let { org.fossify.clock.ha.HaListOrder.longDate(it) + " · " } ?: "") + countText
                alarmDays.setTextColor(textColor)
                alarmLabel.text = info?.groupName ?: group
                alarmLabel.setTextColor(textColor)
                alarmLabel.beVisibleIf(true)
                alarmLabel.setTypeface(null, android.graphics.Typeface.BOLD)
                val fade = if (sum.on == 0) FADE_OFF else 1f
                alarmTime.alpha = fade
                alarmDays.alpha = fade
                alarmLabel.alpha = fade
                alarmSwitch.beVisibleIf(false)
                return@apply
            }
            alarmLabel.setTypeface(null, android.graphics.Typeface.NORMAL)
            alarmSwitch.beVisibleIf(true)
            val skippedAt = info?.skippedAt?.takeIf { org.fossify.clock.ha.HaSkipLogic.isSkipActive(it, now) }

            alarmTime.text = activity.getFormattedTime(
                passedSeconds = alarm.timeInMinutes * 60,
                showSeconds = false,
                makeAmPmSmaller = true
            )
            alarmTime.setTextColor(textColor)

            alarmDays.text = when {
                skippedAt != null -> skippedText(skippedAt)
                info != null -> org.fossify.clock.ha.HaListOrder.dayText(haItem(alarm), dayNames(alarm), now)
                else -> getAlarmSelectedDaysString(alarm)
            }
            val fade = if (alarm.isEnabled && skippedAt == null) 1f else if (info != null) FADE_OFF else 1f
            alarmTime.alpha = fade
            alarmDays.alpha = fade
            alarmLabel.alpha = fade
            alarmSwitch.alpha = 1f
            alarmSwitch.isEnabled = true
            alarmDays.setTextColor(textColor)

            alarmLabel.text = alarm.label
            alarmLabel.setTextColor(textColor)
            alarmLabel.beVisibleIf(alarm.label.isNotEmpty())

            alarmSwitch.isChecked = alarm.isEnabled && skippedAt == null
            alarmSwitch.setColors(textColor, properPrimaryColor, backgroundColor)
            alarmSwitch.setOnClickListener {
                if (info != null && (alarm.isEnabled || skippedAt != null)) {
                    // Alarme da agenda: desligar = pular só o próximo toque; ligar = desfazer o pulo.
                    org.fossify.clock.ha.HaSkip.toggle(activity, alarm.id, alarmSwitch.isChecked)
                } else {
                    toggleAlarm(binding = this, alarm = alarm)
                }
            }
        }
    }

    private fun dayNames(alarm: Alarm): String? = if (alarm.days > 0) activity.getSelectedDaysString(alarm.days) else null

    /** Cartão de cantos arredondados para grupo do HA; alarme solto volta ao fundo original. */
    private fun styleCard(view: View, card: Boolean) {
        if (view.getTag(R.id.alarm_holder) == null) view.setTag(R.id.alarm_holder, view.background ?: android.graphics.drawable.ColorDrawable(0))
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        val margin = (8 * activity.resources.displayMetrics.density).toInt()
        if (card) {
            view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * activity.resources.displayMetrics.density
                setColor(textColor.adjustAlpha(0.08f))
            }
            params?.setMargins(margin, margin / 2, margin, margin / 2)
        } else {
            view.background = view.getTag(R.id.alarm_holder) as android.graphics.drawable.Drawable
            params?.setMargins(0, 0, 0, 0)
        }
        view.layoutParams = params ?: view.layoutParams
    }

    private fun toggleAlarm(binding: ItemAlarmBinding, alarm: Alarm) {
        when {
            alarm.isRecurring() -> {
                if (activity.config.wasAlarmWarningShown) {
                    toggleAlarmInterface.alarmToggled(alarm.id, binding.alarmSwitch.isChecked)
                } else {
                    ConfirmationDialog(
                        activity = activity,
                        messageId = org.fossify.commons.R.string.alarm_warning,
                        positive = org.fossify.commons.R.string.ok,
                        negative = 0
                    ) {
                        activity.config.wasAlarmWarningShown = true
                        toggleAlarmInterface.alarmToggled(alarm.id, binding.alarmSwitch.isChecked)
                    }
                }
            }

            else -> {
                updateNonRecurringAlarmDay(alarm)
                activity.dbHelper.updateAlarm(alarm)
                binding.alarmDays.text = getAlarmSelectedDaysString(
                    alarm = alarm, isEnabled = binding.alarmSwitch.isChecked
                )
                toggleAlarmInterface.alarmToggled(alarm.id, binding.alarmSwitch.isChecked)
            }
        }
    }

    private fun haItem(alarm: Alarm): org.fossify.clock.ha.HaListOrder.Item {
        val info = haInfo[alarm.id]
        return org.fossify.clock.ha.HaListOrder.Item(
            alarm.id, alarm.timeInMinutes, alarm.days, alarm.isEnabled, info?.group, info?.skippedAt
        )
    }

    private fun skippedText(at: Long): String {
        val day = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val today = java.util.Calendar.getInstance()
        fun same(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (same(day, today)) return activity.getString(R.string.ha_skipped_today)
        today.add(java.util.Calendar.DAY_OF_MONTH, 1)
        if (same(day, today)) return activity.getString(R.string.ha_skipped_tomorrow)
        val fmt = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault())
        return activity.getString(R.string.ha_skipped_on, fmt.format(day.time))
    }

    private fun getAlarmSelectedDaysString(
        alarm: Alarm, isEnabled: Boolean = alarm.isEnabled,
    ): String {
        if (alarm.isRecurring()) {
            return if (alarm.days == EVERY_DAY_BIT) {
                activity.getString(org.fossify.commons.R.string.every_day)
            } else {
                // TODO: This does not respect config.firstDayOfWeek
                activity.getSelectedDaysString(alarm.days)
            }
        }

        return when {
            !isEnabled -> resources.getString(R.string.not_scheduled)
            alarm.isToday() -> resources.getString(org.fossify.commons.R.string.today)
            else -> resources.getString(org.fossify.commons.R.string.tomorrow)
        }
    }

    override fun onRowMoved(fromPosition: Int, toPosition: Int) {
        alarms.move(fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        saveAlarmsCustomOrder(alarms)
        if (activity.config.alarmSort != SORT_BY_CUSTOM) {
            activity.config.alarmSort = SORT_BY_CUSTOM
        }
    }

    private fun saveAlarmsCustomOrder(alarms: ArrayList<Alarm>) {
        val alarmsCustomSortingIds = alarms.map { it.id }

        activity.config.alarmsCustomSorting = alarmsCustomSortingIds.joinToString { it.toString() }
    }

    companion object {
        const val FADE_OFF = 0.35f
    }
}
