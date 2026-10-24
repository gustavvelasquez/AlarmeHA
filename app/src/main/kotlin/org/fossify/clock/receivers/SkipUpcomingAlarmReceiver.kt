package org.fossify.clock.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.ha.HaSkip
import org.fossify.clock.models.AlarmEvent
import org.greenrobot.eventbus.EventBus
import org.fossify.clock.extensions.goAsync
import org.fossify.clock.extensions.hideNotification
import org.fossify.clock.helpers.ALARM_ID
import org.fossify.clock.helpers.NOTIFICATION_ID

/**
 * Receiver responsible for dismissing *UPCOMING* alarms.
 */
class SkipUpcomingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(ALARM_ID, -1)
        if (alarmId != -1) {
            goAsync {
                // AlarmeHA 0.13.0: alarme da agenda pula pelo mesmo caminho do interruptor (fica
                // registrado, avisa o HA e vale só para o próximo toque).
                val alarm = context.dbHelper.getAlarmWithId(alarmId)
                if (alarm != null && HaSkip.isHaAlarm(context, alarmId)) {
                    if (HaSkip.skipNow(context, alarm, origem = HaSkip.ORIGEM_NOTIFICACAO)) {
                        context.updateWidgets()
                        EventBus.getDefault().post(AlarmEvent.Refresh)
                    }
                } else {
                    context.alarmController.skipNextOccurrence(alarmId)
                }
            }
        }

        val notificationId = intent.getIntExtra(NOTIFICATION_ID, -1)
        if (notificationId != -1) {
            context.hideNotification(notificationId)
        }
    }
}
