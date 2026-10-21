package org.fossify.clock.ha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.extensions.dbHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Calendar

/**
 * Gera PNGs 1080x2340 do visual 0.9.0 com dados de exemplo. Roda só com
 * ./gradlew :app:testFossDebugUnitTest -Palarmeha.shots=/pasta --tests '*HaScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w360dp-h780dp-night-xxhdpi")
class HaScreenshotTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out get() = File(System.getProperty("alarmeha.shots")!!).apply { mkdirs() }

    private fun bit(daysFromToday: Int): Int {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, daysFromToday) }
        return 1 shl ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7)
    }

    private fun at(daysFromToday: Int, h: Int, m: Int) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, daysFromToday); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun add(ref: String?, h: Int, m: Int, days: Int, label: String, on: Boolean = true, group: String? = null, groupName: String? = null, skipped: Long? = null): Int {
        val a = ctx.createNewAlarm(h * 60 + m, days).apply { isEnabled = on; this.label = label }
        val id = ctx.dbHelper.insertAlarm(a)
        if (ref != null) {
            val refs = HaRefStore.get(ctx)
            refs.put(ref, id)
            refs.setGroup(ref, group, groupName)
            if (skipped != null) refs.setSkipped(id, skipped)
        }
        return id
    }

    private fun seed() {
        HaConfig(ctx).markActivated()
        Shadows.shadowOf(ctx.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
        ctx.dbHelper.deleteAlarms(ArrayList(ctx.dbHelper.getAlarms()))
        val trab = "trabalho_plantao_amanha"
        listOf(0, 10, 20, 30).forEachIndexed { i, m ->
            add("ag_trabalho_${i + 1}", 7, m, -2, "📅 Plantão ${i + 1}/4", group = trab, groupName = "Trabalho · Plantão · amanhã")
        }
        val hojeAmanha = bit(0) or bit(1)
        add("ag_remedio_1", 9, 0, hojeAmanha, "Remédio manhã", group = "tipo_remedio", groupName = "Remédio")
        add("ag_remedio_2", 18, 0, hojeAmanha, "Remédio tarde", group = "tipo_remedio", groupName = "Remédio", skipped = at(0, 18, 0))
        add("ag_remedio_3", 21, 0, -1, "Remédio noite", group = "tipo_remedio", groupName = "Remédio")
        add(null, 6, 30, bit(-2) or bit(0) or bit(2), "Caminhada", on = false)
        add(null, 14, 0, -2, "Dentista")
    }

    private fun settle() {
        repeat(6) { Thread.sleep(300); ShadowLooper.idleMainLooper() }
    }

    private fun shot(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2340)
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(HaStyle.BG); view.draw(this) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun lista_e_grupo() {
        seed()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        main.get().findViewById<androidx.viewpager.widget.ViewPager>(org.fossify.clock.R.id.view_pager).currentItem = 1
        settle()
        shot(main.get().window.decorView, "lista")

        val grupo = Robolectric.buildActivity(HaGroupActivity::class.java, HaGroupActivity.intent(ctx, "tipo_remedio")).setup()
        settle()
        shot(grupo.get().window.decorView, "grupo")

        val trab = Robolectric.buildActivity(HaGroupActivity::class.java, HaGroupActivity.intent(ctx, "trabalho_plantao_amanha")).setup()
        settle()
        shot(trab.get().window.decorView, "grupo_trabalho")
    }
}
