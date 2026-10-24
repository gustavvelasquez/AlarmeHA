package org.fossify.clock.ha

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.fossify.clock.R
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.extensions.getOpenAlarmTabIntent
import org.fossify.clock.helpers.MISSED_ALARM_NOTIFICATION_CHANNEL_ID
import org.fossify.clock.models.Alarm
import org.fossify.commons.extensions.notificationManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.13.0: aviso de alarmes perdidos. Um alarme que tocou até o fim do tempo sem ser desligado nem
 * adiado entra numa lista; uma única notificação mostra todos ("2 alarmes perdidos: 03:33 e 03:43")
 * e fica até o app ser aberto ou a notificação ser limpa. Alarme pulado nunca toca, então não entra.
 */
object HaMissed {
    private const val PREFS = "alarmeha_missed"
    private const val KEY_LIST = "list"
    private const val NOTIFICATION_TAG = "alarmeha_missed"
    private const val NOTIFICATION_ID = 13013
    const val MAX_ITEMS = 20

    data class Item(val alarmId: Int, val minutes: Int, val label: String)

    // ---- lógica pura (testada) ----

    /** Acrescenta mantendo a ordem de chegada; guarda só os últimos [MAX_ITEMS]. */
    fun append(list: List<Item>, item: Item): List<Item> = (list + item).takeLast(MAX_ITEMS)

    /** "03:33", "03:33 e 03:43", "03:33, 03:43 e 04:03". */
    fun joinTimes(times: List<String>, and: String): String = when (times.size) {
        0 -> ""
        1 -> times[0]
        else -> times.dropLast(1).joinToString(", ") + and + times.last()
    }

    fun encode(list: List<Item>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.alarmId).put("m", it.minutes).put("l", it.label)) }
    }.toString()

    fun decode(raw: String?): List<Item> = runCatching {
        val arr = JSONArray(raw ?: return emptyList())
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Item(it.getInt("id"), it.getInt("m"), it.optString("l")) }
        }
    }.getOrDefault(emptyList())

    // ---- celular ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun items(context: Context): List<Item> = decode(prefs(context).getString(KEY_LIST, null))

    @Synchronized
    fun add(context: Context, alarm: Alarm) {
        val list = append(items(context), Item(alarm.id, alarm.timeInMinutes, alarm.label))
        prefs(context).edit().putString(KEY_LIST, encode(list)).apply()
        post(context, list)
    }

    /** Abriu o app ou limpou a notificação: zera a lista. */
    @Synchronized
    fun clear(context: Context) {
        if (prefs(context).contains(KEY_LIST)) prefs(context).edit().remove(KEY_LIST).apply()
        runCatching { context.notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID) }
    }

    private fun post(context: Context, list: List<Item>) {
        if (list.isEmpty()) return
        val time = { m: Int -> context.getFormattedTime(passedSeconds = m * 60, showSeconds = false, makeAmPmSmaller = false).toString() }
        val times = joinTimes(list.map { time(it.minutes) }, context.getString(R.string.ha_missed_and))
        val title = if (list.size == 1) context.getString(R.string.ha_missed_one, times)
        else context.getString(R.string.ha_missed_many, list.size, times)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        list.forEach { item ->
            style.addLine(if (item.label.isBlank()) time(item.minutes) else "${time(item.minutes)}  ${item.label}")
        }
        val clearIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID, Intent(context, ClearReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MISSED_ALARM_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.alarm_timed_out))
            .setStyle(style)
            .setNumber(list.size)
            .setSmallIcon(R.drawable.ic_alarm_off_vector)
            .setContentIntent(context.getOpenAlarmTabIntent())
            .setDeleteIntent(clearIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(false)
            .setShowWhen(true)
            .setAutoCancel(true)
            .build()
        context.notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
    }

    class ClearReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = clear(context)
    }
}
