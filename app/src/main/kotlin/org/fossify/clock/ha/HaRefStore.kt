package org.fossify.clock.ha

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tabela ha_alarm_ref: liga o identificador externo do HA (ref) ao id interno do alarme.
 * Fica num banco separado para não alterar o banco do Fossify Clock.
 */
class HaRefStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE ($COL_REF TEXT PRIMARY KEY, $COL_ALARM_ID INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun getAlarmId(ref: String): Int? =
        readableDatabase.query(TABLE, arrayOf(COL_ALARM_ID), "$COL_REF = ?", arrayOf(ref), null, null, null)
            .use { if (it.moveToFirst()) it.getInt(0) else null }

    fun put(ref: String, alarmId: Int) {
        val values = ContentValues().apply {
            put(COL_REF, ref)
            put(COL_ALARM_ID, alarmId)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(ref: String) {
        writableDatabase.delete(TABLE, "$COL_REF = ?", arrayOf(ref))
    }

    /** ref -> alarmId */
    fun all(): Map<String, Int> =
        readableDatabase.query(TABLE, arrayOf(COL_REF, COL_ALARM_ID), null, null, null, null, null).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
        }

    fun refForAlarm(alarmId: Int): String? = all().entries.firstOrNull { it.value == alarmId }?.key

    companion object {
        private const val DB_NAME = "alarmeha_ha.db"
        private const val DB_VERSION = 1
        private const val TABLE = "ha_alarm_ref"
        private const val COL_REF = "ref"
        private const val COL_ALARM_ID = "alarm_id"

        @Volatile
        private var instance: HaRefStore? = null

        fun get(context: Context): HaRefStore =
            instance ?: synchronized(this) { instance ?: HaRefStore(context).also { instance = it } }
    }
}
