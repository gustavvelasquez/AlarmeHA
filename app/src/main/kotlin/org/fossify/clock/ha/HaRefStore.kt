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
        addV2Columns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addV2Columns(db)
    }

    /** v2 (0.4.0): grupo do compromisso e toque pulado (skip once, epoch ms). */
    private fun addV2Columns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_GROUP TEXT")
        db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_GROUP_NAME TEXT")
        db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_SKIPPED INTEGER")
    }

    data class Info(val ref: String, val alarmId: Int, val group: String?, val groupName: String?, val skippedAt: Long?)

    fun infoForAlarm(alarmId: Int): Info? = infos("$COL_ALARM_ID = ?", arrayOf(alarmId.toString())).firstOrNull()

    fun allInfo(): List<Info> = infos(null, null)

    private fun infos(where: String?, args: Array<String>?): List<Info> =
        readableDatabase.query(
            TABLE, arrayOf(COL_REF, COL_ALARM_ID, COL_GROUP, COL_GROUP_NAME, COL_SKIPPED), where, args, null, null, null
        ).use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        Info(
                            it.getString(0), it.getInt(1),
                            it.getString(2)?.takeIf { g -> g.isNotEmpty() }, it.getString(3),
                            if (it.isNull(4)) null else it.getLong(4),
                        )
                    )
                }
            }
        }

    fun setGroup(ref: String, group: String?, groupName: String?) {
        val values = ContentValues().apply {
            if (group == null) putNull(COL_GROUP) else put(COL_GROUP, group)
            if (groupName == null) putNull(COL_GROUP_NAME) else put(COL_GROUP_NAME, groupName)
        }
        writableDatabase.update(TABLE, values, "$COL_REF = ?", arrayOf(ref))
    }

    fun setSkipped(alarmId: Int, skippedAt: Long?) {
        val values = ContentValues().apply { if (skippedAt == null) putNull(COL_SKIPPED) else put(COL_SKIPPED, skippedAt) }
        writableDatabase.update(TABLE, values, "$COL_ALARM_ID = ?", arrayOf(alarmId.toString()))
    }

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
        private const val DB_VERSION = 2
        private const val TABLE = "ha_alarm_ref"
        private const val COL_REF = "ref"
        private const val COL_ALARM_ID = "alarm_id"
        private const val COL_GROUP = "grupo"
        private const val COL_GROUP_NAME = "grupo_nome"
        private const val COL_SKIPPED = "pulado"

        @Volatile
        private var instance: HaRefStore? = null

        fun get(context: Context): HaRefStore =
            instance ?: synchronized(this) { instance ?: HaRefStore(context).also { instance = it } }
    }
}
