package org.fossify.clock.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.adapters.AlarmsAdapter
import org.fossify.clock.R
import org.fossify.clock.databinding.FragmentAlarmBinding
import org.fossify.clock.dialogs.ChangeAlarmSortDialog
import org.fossify.clock.dialogs.EditAlarmDialog
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.firstDayOrder
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.helpers.DEFAULT_ALARM_MINUTES
import org.fossify.clock.helpers.SORT_BY_ALARM_TIME
import org.fossify.clock.helpers.SORT_BY_DATE_AND_TIME
import org.fossify.clock.helpers.getTomorrowBit
import org.fossify.clock.interfaces.ToggleAlarmInterface
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.AlarmEvent
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.SORT_BY_CUSTOM
import org.fossify.commons.helpers.SORT_BY_DATE_CREATED
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.AlarmSound
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class AlarmFragment : Fragment(), ToggleAlarmInterface {
    private var alarms = ArrayList<Alarm>()
    private var currentEditAlarmDialog: EditAlarmDialog? = null

    private lateinit var binding: FragmentAlarmBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        setupViews()
    }

    fun showSortingDialog() {
        ChangeAlarmSortDialog(activity as SimpleActivity) {
            setupAlarms()
        }
    }

    private fun setupViews() {
        binding.apply {
            requireContext().updateTextColors(alarmFragment)
            alarmFab.setOnClickListener {
                val newAlarm = root.context.createNewAlarm(DEFAULT_ALARM_MINUTES, 0)
                newAlarm.isEnabled = true
                newAlarm.days = getTomorrowBit()
                openEditAlarm(newAlarm)
            }
        }

        setupAlarms()
    }

    private fun getSortedAlarms(callback: (alarms: ArrayList<Alarm>) -> Unit) {
        val safeContext = context ?: return
        ensureBackgroundThread {
            var newAlarms = context?.dbHelper?.getAlarms()
            if (newAlarms == null) {
                activity?.runOnUiThread {
                    callback(arrayListOf())
                }
                return@ensureBackgroundThread
            }

            when (safeContext.config.alarmSort) {
                SORT_BY_ALARM_TIME -> newAlarms.sortBy { it.timeInMinutes }
                SORT_BY_DATE_CREATED -> newAlarms.sortBy { it.id }
                SORT_BY_DATE_AND_TIME -> newAlarms.sortWith(compareBy<Alarm> {
                    safeContext.firstDayOrder(it.days)
                }.thenBy {
                    it.timeInMinutes
                })

                SORT_BY_CUSTOM -> {
                    val customAlarmsSortOrderString = activity?.config?.alarmsCustomSorting
                    if (customAlarmsSortOrderString == "") {
                        newAlarms.sortBy { it.id }
                    } else {
                        val customAlarmsSortOrder: List<Int> =
                            customAlarmsSortOrderString?.split(", ")?.map { it.toInt() }!!
                        val alarmsIdValueMap = newAlarms.associateBy { it.id }

                        val sortedAlarms: ArrayList<Alarm> = ArrayList()
                        customAlarmsSortOrder.map { id ->
                            if (alarmsIdValueMap[id] != null) {
                                sortedAlarms.add(alarmsIdValueMap[id] as Alarm)
                            }
                        }

                        newAlarms =
                            (sortedAlarms + newAlarms.filter { it !in sortedAlarms }) as ArrayList<Alarm>
                    }
                }
            }

            // AlarmeHA: com alarmes do HA, a lista segue o próximo toque; cada compromisso é um bloco.
            val info = runCatching { org.fossify.clock.ha.HaRefStore.get(safeContext).allInfo().associateBy { it.alarmId } }
                .getOrDefault(emptyMap())
            haInfo = info
            val sorted = newAlarms!!
            val ordered = if (info.isEmpty()) {
                sorted
            } else {
                val groups = org.fossify.clock.ha.HaSkipLogic.groupsOf(info.mapValues { it.value.group }).keys
                val items = sorted.map {
                    org.fossify.clock.ha.HaListOrder.Item(
                        it.id, it.timeInMinutes, it.days, it.isEnabled, info[it.id]?.group, info[it.id]?.skippedAt
                    )
                }
                val byId = sorted.associateBy { it.id }
                val order = org.fossify.clock.ha.HaListOrder.order(items, groups, System.currentTimeMillis())
                // Um cartão por compromisso (grupo); manuais soltos.
                val cards = org.fossify.clock.ha.HaListOrder.cards(order, info.mapValues { it.value.group }, groups)
                ArrayList(cards.mapNotNull { byId[it] })
            }
            allAlarms = sorted

            activity?.runOnUiThread {
                callback(ordered)
            }
        }
    }

    private var haInfo: Map<Int, org.fossify.clock.ha.HaRefStore.Info> = emptyMap()
    private var allAlarms: List<Alarm> = emptyList()

    private fun setupAlarms() {
        val ctx = context
        if (ctx != null) {
            val banner = org.fossify.clock.ha.HaDiagnostics.banner(ctx)
            binding.alarmsHaBanner.beVisibleIf(banner != null)
            binding.alarmsHaBanner.text = banner ?: ""
            binding.alarmsHaBanner.setTextColor(ctx.getProperPrimaryColor())
            binding.alarmsHaBanner.setOnClickListener {
                val battery = org.fossify.clock.ha.HaDiagnostics.batteryProblem(ctx) != null
                startActivity(android.content.Intent(ctx, if (battery) org.fossify.clock.ha.HaPermissionsActivity::class.java else org.fossify.clock.ha.HaPairingActivity::class.java))
            }
        }
        getSortedAlarms { sortedAlarms ->
            alarms = sortedAlarms
            val safeActivity = activity as? SimpleActivity ?: return@getSortedAlarms
            var currAdapter = binding.alarmsList.adapter as? AlarmsAdapter
            if (currAdapter == null) {
                currAdapter = AlarmsAdapter(
                    activity = safeActivity,
                    alarms = alarms,
                    toggleAlarmInterface = this,
                    recyclerView = binding.alarmsList
                ) {
                    openAlarm(it as Alarm)
                }.apply {
                    updateHaInfo(haInfo, allAlarms)
                    binding.alarmsList.adapter = this
                }
            } else {
                currAdapter.apply {
                    updatePrimaryColor()
                    updateBackgroundColor(safeActivity.getProperBackgroundColor())
                    updateTextColor(safeActivity.getProperTextColor())
                    updateHaInfo(haInfo, allAlarms)
                    updateItems(alarms)
                }
            }
            binding.alarmsPlaceholder.beVisibleIf(alarms.isEmpty())
        }
    }

    /** Grupo do HA abre a tela do grupo; alarme do HA solto não é editável; manual abre a edição. */
    private fun openAlarm(alarm: Alarm) {
        val info = haInfo[alarm.id]
        val group = info?.group
        when {
            group != null -> startActivity(org.fossify.clock.ha.HaGroupActivity.intent(requireContext(), group))
            info != null -> context?.toast(R.string.ha_alarm_from_agenda)
            else -> openEditAlarm(alarm)
        }
    }

    private fun openEditAlarm(alarm: Alarm) {
        currentEditAlarmDialog = EditAlarmDialog(activity as SimpleActivity, alarm) {
            alarm.id = it
            currentEditAlarmDialog = null
            setupAlarms()
            checkAlarmState(alarm)
        }
    }

    override fun alarmToggled(id: Int, isEnabled: Boolean) {
        (activity as SimpleActivity).handleFullScreenNotificationsPermission { granted ->
            if (granted) {
                if (requireContext().dbHelper.updateAlarmEnabledState(id, isEnabled)) {
                    val alarm = alarms.firstOrNull { it.id == id }
                        ?: return@handleFullScreenNotificationsPermission
                    alarm.isEnabled = isEnabled
                    checkAlarmState(alarm)
                    if (!alarm.isEnabled && alarm.oneShot) {
                        requireContext().dbHelper.deleteAlarms(arrayListOf(alarm))
                        setupAlarms()
                    }
                } else {
                    requireActivity().toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
                requireContext().updateWidgets()
            } else {
                setupAlarms()
            }
        }
    }

    private fun checkAlarmState(alarm: Alarm) {
        val activity = activity as? MainActivity ?: return
        if (alarm.isEnabled) {
            activity.alarmController.scheduleNextOccurrence(alarm = alarm, showToasts = true)
        } else {
            activity.cancelAlarmClock(alarm)
        }
        activity.updateClockTabAlarm()
    }

    fun updateAlarmSound(alarmSound: AlarmSound) {
        currentEditAlarmDialog?.updateSelectedAlarmSound(alarmSound)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(@Suppress("unused") event: AlarmEvent.Refresh) {
        setupAlarms()
    }
}
