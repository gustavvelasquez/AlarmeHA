package org.fossify.clock.ha

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Roda antes de tudo (até do WorkManager/androidx.startup) só para instalar o registro de erros fatais. */
class HaEarlyInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { HaCrashLog.install(it) }
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
