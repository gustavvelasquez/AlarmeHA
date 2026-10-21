package org.fossify.clock.adapters

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.clock.R
import org.fossify.clock.ha.HaListOrder
import org.fossify.clock.ha.HaSkipLogic
import org.fossify.clock.ha.HaStyle
import org.fossify.clock.ha.HaUi
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
        val now = System.currentTimeMillis()
        cardTitles = HaUi.cardTitles(haGroups.keys.map { g ->
            val members = all.filter { info[it.id]?.group == g }.map { haItem(it) }
            HaUi.Card(g, members.firstNotNullOfOrNull { m -> info[m.alarmId]?.groupName }, HaListOrder.summary(members, now).nextRing)
        })
    }

    private var cardTitles: Map<String, Pair<String, String?>> = emptyMap()

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
        val ctx = activity
        ItemAlarmBinding.bind(view).apply {
            alarmHolder.isSelected = isSelected
            alarmDragHandle.beVisibleIf(selectedKeys.isNotEmpty())
            alarmDragHandle.applyColorFilter(HaStyle.TEXT)
            alarmDragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    startReorderDragListener.requestDrag(holder)
                }
                false
            }
            val info = haInfo[alarm.id]
            val group = haGroupOf(alarm)
            val now = System.currentTimeMillis()
            haGroupPill.beVisibleIf(group != null)
            alarmSolo.beVisibleIf(group == null)
            if (group != null) {
                // Cartão do grupo (pílula da Samsung): ícone roxo, nome do tipo e o total de alarmes. Sem chave.
                val members = allAlarms.filter { haInfo[it.id]?.group == group }
                val (name, hint) = cardTitles[group] ?: (HaUi.baseName(info?.groupName, group) to null)
                haGroupPill.background = HaStyle.rounded(ctx, HaStyle.CARD, 30f)
                haGroupAvatar.background = HaStyle.rounded(ctx, 0xFF7F7CF0.toInt(), 23f)
                haGroupName.text = name
                haGroupName.setTextColor(HaStyle.ACCENT)
                haGroupHint.beVisibleIf(hint != null)
                haGroupHint.text = hint ?: ""
                haGroupHint.setTextColor(HaStyle.TEXT_2)
                haGroupCount.text = members.size.toString()
                haGroupCount.setTextColor(HaStyle.ACCENT)
                haGroupCountIcon.applyColorFilter(HaStyle.ACCENT)
                haGroupPill.contentDescription = "$name, ${HaUi.countDescription(members.size)}"
                return@apply
            }

            // Solto: fatia do cartão único dos soltos (divisória entre eles).
            val pos = alarms.indexOf(alarm)
            val prevSolo = pos > 0 && haGroupOf(alarms[pos - 1]) == null
            val nextSolo = pos >= 0 && pos < alarms.size - 1 && haGroupOf(alarms[pos + 1]) == null
            alarmSolo.background = HaStyle.segment(ctx, if (isSelected) HaStyle.BUTTON else HaStyle.CARD, !prevSolo, !nextSolo)
            alarmDivider.beVisibleIf(prevSolo)
            alarmDivider.setBackgroundColor(HaStyle.DIVIDER)
            (alarmSolo.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = if (prevSolo || pos == 0) 0 else (8 * ctx.resources.displayMetrics.density).toInt()
                alarmSolo.layoutParams = it
            }

            val skippedAt = info?.skippedAt?.takeIf { HaSkipLogic.isSkipActive(it, now) }
            val on = alarm.isEnabled && skippedAt == null
            alarmTime.text = HaUi.hhmm(alarm.timeInMinutes)
            alarmTime.typeface = HaStyle.boldTime()
            alarmTime.setTextColor(if (on) HaStyle.TEXT else HaStyle.DIM)
            alarmLabel.text = alarm.label
            alarmLabel.setTextColor(if (on) HaStyle.TEXT else HaStyle.DIM)
            alarmLabel.beVisibleIf(alarm.label.isNotEmpty())
            alarmChip.beVisibleIf(skippedAt != null)
            alarmChip.background = HaStyle.rounded(ctx, 0xFF2C2C30.toInt(), 9f)
            alarmChip.setTextColor(HaStyle.TEXT_2)
            alarmLabelRow.beVisibleIf(alarm.label.isNotEmpty() || skippedAt != null)

            val recurring = alarm.days > 0 && skippedAt == null
            alarmWeek.beVisibleIf(recurring)
            alarmWeek.setDays(alarm.days)
            alarmWeek.faded = !on
            alarmDate.beVisibleIf(!recurring)
            alarmDate.text = when {
                skippedAt != null -> HaUi.skippedLine(haItem(alarm), now)
                else -> oneTimeDate(alarm, now)
            }
            alarmDate.setTextColor(if (on) HaStyle.ACCENT else HaStyle.DIM)

            alarmSwitch.alpha = 1f
            alarmSwitch.isEnabled = true
            alarmSwitch.isChecked = on
            HaStyle.paintSwitch(alarmSwitch)
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

    /** Alarme de uma vez: data por extenso do próximo toque ("qui., 22 de out."). */
    private fun oneTimeDate(alarm: Alarm, now: Long): String {
        val at = HaSkipLogic.nextOccurrence(alarm.timeInMinutes, alarm.days, now) ?: return ""
        return HaListOrder.longDate(at)
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
                binding.alarmDate.text = oneTimeDate(alarm, System.currentTimeMillis())
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
