package org.fossify.clock.activities

import android.annotation.SuppressLint
import org.fossify.commons.extensions.beVisibleIf
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.core.graphics.drawable.toDrawable
import me.grantland.widget.AutofitHelper
import org.fossify.clock.BuildConfig
import org.fossify.clock.R
import org.fossify.clock.adapters.ViewPagerAdapter
import org.fossify.clock.databinding.ActivityMainBinding
import org.fossify.clock.ha.HaCrashLog
import org.fossify.clock.ha.HaOnboarding
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.getEnabledAlarms
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.helpers.INVALID_TIMER_ID
import org.fossify.clock.helpers.OPEN_TAB
import org.fossify.clock.helpers.PICK_AUDIO_FILE_INTENT_ID
import org.fossify.clock.helpers.STOPWATCH_SHORTCUT_ID
import org.fossify.clock.helpers.STOPWATCH_TOGGLE_ACTION
import org.fossify.clock.helpers.TABS_COUNT
import org.fossify.clock.helpers.TAB_ALARM
import org.fossify.clock.helpers.TAB_ALARM_INDEX
import org.fossify.clock.helpers.TAB_CLOCK
import org.fossify.clock.helpers.TAB_CLOCK_INDEX
import org.fossify.clock.helpers.TAB_STOPWATCH
import org.fossify.clock.helpers.TAB_STOPWATCH_INDEX
import org.fossify.clock.helpers.TAB_TIMER
import org.fossify.clock.helpers.TAB_TIMER_INDEX
import org.fossify.clock.helpers.TIMER_ID
import org.fossify.clock.helpers.TOGGLE_STOPWATCH
import org.fossify.commons.databinding.BottomTablayoutItemBinding
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onPageChangeListener
import org.fossify.commons.extensions.onTabSelectionChanged
import org.fossify.commons.extensions.shortcutManager
import org.fossify.commons.extensions.storeNewYourAlarmSound
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateBottomTabItemColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_NUMBER_PICKER
import org.fossify.commons.helpers.LICENSE_RTL
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FAQItem
import java.time.temporal.WeekFields
import java.util.Locale

class MainActivity : SimpleActivity() {
    private var storedTextColor = 0
    private var storedBackgroundColor = 0
    private var storedPrimaryColor = 0
    private val binding: ActivityMainBinding by viewBinding(ActivityMainBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        HaCrashLog.install(this)
        super.onCreate(savedInstanceState)
        if (HaOnboarding.redirectIfNeverActivated(this)) return
        if (org.fossify.clock.ha.HaPermissions.redirectIfMissing(this)) return
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.haTabWrap))

        storeStateVariables()
        initFragments()
        setupTabs()
        updateWidgets()
        migrateFirstDayOfWeek()
        ensureBackgroundThread {
            alarmController.rescheduleEnabledAlarms()
        }

        getEnabledAlarms { enabledAlarms ->
            if (!enabledAlarms.isNullOrEmpty()) {
                handleFullScreenNotificationsPermission {
                    if (!it) {
                        toast(org.fossify.commons.R.string.notifications_disabled)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.mainAppbar, topBarColor = getProperBackgroundColor())
        val configTextColor = getProperTextColor()
        if (storedTextColor != configTextColor) {
            getInactiveTabIndexes(binding.viewPager.currentItem).forEach {
                binding.mainTabsHolder.getTabAt(it)?.icon?.applyColorFilter(configTextColor)
            }
        }

        val configBackgroundColor = getProperBackgroundColor()
        if (storedBackgroundColor != configBackgroundColor) {
            Unit
        }

        val configPrimaryColor = getProperPrimaryColor()
        if (storedPrimaryColor != configPrimaryColor) {
            binding.mainTabsHolder.setSelectedTabIndicatorColor(getProperPrimaryColor())
            binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.icon
                ?.applyColorFilter(getProperPrimaryColor())
        }

        if (config.preventPhoneFromSleeping) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setupTabColors()
        checkShortcuts()
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (config.lastHandledShortcutColor != appIconColor) {
            val stopWatchShortcutInfo = getLaunchStopwatchShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(stopWatchShortcutInfo)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchStopwatchShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.start_stopwatch)
        val drawable = resources.getDrawable(R.drawable.shortcut_stopwatch)
        (drawable as LayerDrawable)
            .findDrawableByLayerId(R.id.shortcut_stopwatch_background)
            .applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, SplashActivity::class.java).apply {
            putExtra(OPEN_TAB, TAB_STOPWATCH)
            putExtra(TOGGLE_STOPWATCH, true)
            action = STOPWATCH_TOGGLE_ACTION
        }

        return ShortcutInfo.Builder(this, STOPWATCH_SHORTCUT_ID)
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        if (config.preventPhoneFromSleeping) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    /** 0.9.0: na aba Alarme a barra some (cabeçalho da Samsung); o ⋮ do cabeçalho abre o mesmo menu. */
    fun showMainMenu(anchor: android.view.View) {
        android.widget.PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu, menu)
            menu.findItem(R.id.more_apps_from_us).isVisible = false
            setOnMenuItemClickListener { handleMenuItem(it.itemId) }
        }.show()
    }

    private fun updateAlarmTabChrome() {
        val alarmTab = binding.viewPager.currentItem == TAB_ALARM_INDEX
        binding.mainAppbar.beVisibleIf(!alarmTab)
        // Sem a barra de cima, o conteúdo não pode ficar deslocado pela altura dela.
        (binding.mainHolder.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let {
            it.behavior = if (alarmTab) null else com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
            binding.mainHolder.layoutParams = it
        }
        if (alarmTab) binding.mainHolder.translationY = 0f
    }

    private fun setupOptionsMenu() {
        binding.mainToolbar.setOnMenuItemClickListener { handleMenuItem(it.itemId) }
    }

    private fun handleMenuItem(itemId: Int): Boolean {
        run {
            when (itemId) {
                R.id.sort -> when (binding.viewPager.currentItem) {
                    TAB_ALARM_INDEX -> getViewPagerAdapter()?.showAlarmSortDialog()
                    TAB_TIMER_INDEX -> getViewPagerAdapter()?.showTimerSortDialog()
                }

                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.home_assistant_activate -> HaOnboarding.openPanel(this)
                R.id.settings -> launchSettings()
                R.id.about -> showAlarmeHaAbout()
                else -> return false
            }
        }
        return true
    }

    private fun refreshMenuItems() {
        binding.mainToolbar.menu.apply {
            findItem(R.id.sort).isVisible = binding.viewPager.currentItem == getTabIndex(TAB_ALARM)
                    || binding.viewPager.currentItem == getTabIndex(TAB_TIMER)
            // AlarmeHA: sem link para outros apps do Fossify
            findItem(R.id.more_apps_from_us).isVisible = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.extras?.containsKey(OPEN_TAB) == true) {
            val tabToOpen = intent.getIntExtra(OPEN_TAB, TAB_CLOCK)
            binding.viewPager.setCurrentItem(getTabIndex(tabToOpen), false)
            if (tabToOpen == TAB_TIMER) {
                val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
                (binding.viewPager.adapter as ViewPagerAdapter).updateTimerPosition(timerId)
            }
            if (tabToOpen == TAB_STOPWATCH) {
                if (intent.getBooleanExtra(TOGGLE_STOPWATCH, false)) {
                    (binding.viewPager.adapter as ViewPagerAdapter).startStopWatch()
                }
            }
        }
        super.onNewIntent(intent)
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedBackgroundColor = getProperBackgroundColor()
        storedPrimaryColor = getProperPrimaryColor()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        when {
            requestCode == PICK_AUDIO_FILE_INTENT_ID && resultCode == RESULT_OK && resultData != null -> {
                storeNewAlarmSound(resultData)
            }
        }
    }

    private fun storeNewAlarmSound(resultData: Intent) {
        val newAlarmSound = storeNewYourAlarmSound(resultData)

        when (binding.viewPager.currentItem) {
            TAB_ALARM_INDEX -> getViewPagerAdapter()?.updateAlarmTabAlarmSound(newAlarmSound)
            TAB_TIMER_INDEX -> getViewPagerAdapter()?.updateTimerTabAlarmSound(newAlarmSound)
        }
    }

    fun updateClockTabAlarm() {
        getViewPagerAdapter()?.updateClockTabAlarm()
    }

    private fun getViewPagerAdapter() = binding.viewPager.adapter as? ViewPagerAdapter

    private fun initFragments() {
        val viewPagerAdapter = ViewPagerAdapter(supportFragmentManager)
        binding.viewPager.adapter = viewPagerAdapter
        binding.viewPager.onPageChangeListener {
            binding.mainTabsHolder.getTabAt(it)?.select()
            refreshMenuItems()
            updateAlarmTabChrome()
            updateTabPill()
        }

        val tabToOpen = intent.getIntExtra(OPEN_TAB, config.defaultTab)
        intent.removeExtra(OPEN_TAB)
        if (tabToOpen == TAB_TIMER) {
            val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
            viewPagerAdapter.updateTimerPosition(timerId)
        }

        if (tabToOpen == TAB_STOPWATCH) {
            config.toggleStopwatch = intent.getBooleanExtra(TOGGLE_STOPWATCH, false)
        }

        binding.viewPager.offscreenPageLimit = TABS_COUNT - 1
        binding.viewPager.currentItem = getTabIndex(tabToOpen)
        updateAlarmTabChrome()
    }

    private fun setupTabs() {
        setupTabPill()
        binding.mainTabsHolder.removeAllTabs()
        val tabDrawables = arrayOf(
            R.drawable.ic_clock_vector,
            R.drawable.ic_alarm_vector,
            R.drawable.ic_stopwatch_vector,
            R.drawable.ic_hourglass_vector
        )
        val tabLabels = arrayOf(
            R.string.clock,
            org.fossify.commons.R.string.alarm,
            R.string.stopwatch,
            R.string.timer
        )

        tabDrawables.forEachIndexed { i, drawableId ->
            binding.mainTabsHolder.newTab()
                .setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item)
                .apply tab@{
                    customView?.let { BottomTablayoutItemBinding.bind(it) }?.apply {
                        tabItemIcon.setImageDrawable(getDrawable(drawableId))
                        tabItemLabel.setText(tabLabels[i])
                        // 0.9.0: barra em pílula flutuante só com ícones (Samsung).
                        tabItemLabel.beVisibleIf(false)
                        root.contentDescription = getString(tabLabels[i])
                        root.minimumWidth = (76 * resources.displayMetrics.density).toInt()
                        root.minimumHeight = (50 * resources.displayMetrics.density).toInt()
                        root.gravity = android.view.Gravity.CENTER
                        binding.mainTabsHolder.addTab(this@tab)
                    }
                }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                it.customView?.background = null
                updateBottomTabItemColors(
                    view = it.customView,
                    isActive = false,
                    drawableId = getDeselectedTabDrawableIds()[it.position]
                )
            },
            tabSelectedAction = {
                it.customView?.background = org.fossify.clock.ha.HaStyle.rounded(this, org.fossify.clock.ha.HaStyle.PILL_ON, 25f)
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(
                    view = it.customView,
                    isActive = true,
                    drawableId = getSelectedTabDrawableIds()[it.position]
                )
            }
        )
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(
            view = activeView,
            isActive = true,
            drawableId = getSelectedTabDrawableIds()[binding.viewPager.currentItem]
        )

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.select()
        updateTabPill()
    }

    /** 0.9.2: pílula de abas da Samsung (4 ícones brancos; a ativa com fundo mais claro). */
    private fun setupTabPill() {
        val pill = binding.haTabPill
        pill.removeAllViews()
        pill.background = org.fossify.clock.ha.HaStyle.rounded(this, org.fossify.clock.ha.HaStyle.PILL, 30f)
        val d = resources.displayMetrics.density
        val icons = arrayOf(R.drawable.ic_clock_vector, R.drawable.ic_alarm_vector, R.drawable.ic_stopwatch_vector, R.drawable.ic_hourglass_vector)
        val labels = arrayOf(R.string.clock, org.fossify.commons.R.string.alarm, R.string.stopwatch, R.string.timer)
        icons.forEachIndexed { i, icon ->
            android.widget.FrameLayout(this).apply {
                contentDescription = getString(labels[i])
                isClickable = true
                setOnClickListener { binding.viewPager.currentItem = i }
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    applyColorFilter(org.fossify.clock.ha.HaStyle.TEXT)
                }, android.widget.FrameLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt(), android.view.Gravity.CENTER))
            }.also { pill.addView(it, android.widget.LinearLayout.LayoutParams((64 * d).toInt(), (46 * d).toInt()).apply { marginStart = (2 * d).toInt(); marginEnd = (2 * d).toInt() }) }
        }
        updateTabPill()
    }

    private fun updateTabPill() {
        val pill = binding.haTabPill
        for (i in 0 until pill.childCount) {
            pill.getChildAt(i).background = if (i == binding.viewPager.currentItem)
                org.fossify.clock.ha.HaStyle.rounded(this, org.fossify.clock.ha.HaStyle.PILL_ON, 23f) else null
            pill.getChildAt(i).isSelected = i == binding.viewPager.currentItem
        }
    }

    private fun getInactiveTabIndexes(activeIndex: Int): List<Int> {
        return arrayListOf(0, 1, 2, 3).filter { it != activeIndex }
    }

    private fun getSelectedTabDrawableIds() = arrayOf(
        R.drawable.ic_clock_filled_vector,
        R.drawable.ic_alarm_filled_vector,
        R.drawable.ic_stopwatch_filled_vector,
        R.drawable.ic_hourglass_filled_vector
    )

    private fun getDeselectedTabDrawableIds() = arrayOf(
        org.fossify.commons.R.drawable.ic_clock_vector,
        R.drawable.ic_alarm_vector,
        R.drawable.ic_stopwatch_vector,
        R.drawable.ic_hourglass_vector
    )

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    // AlarmeHA: "Sobre" simples, sem links externos; mantém o crédito exigido pela GPL-3.0.
    private fun showAlarmeHaAbout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                "Versão ${BuildConfig.VERSION_NAME}\n\n" +
                    "Baseado no Fossify Clock 1.6.0 (GPL-3.0).\n" +
                    "Este app é software livre, distribuído sob a licença GNU GPL-3.0."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Suppress("UnusedPrivateMember")
    private fun launchAbout() {
        val licenses =
            LICENSE_NUMBER_PICKER or LICENSE_RTL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(
                title = R.string.faq_1_title,
                text = R.string.faq_1_text
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_1_title_commons,
                text = org.fossify.commons.R.string.faq_1_text_commons
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_4_title_commons,
                text = org.fossify.commons.R.string.faq_4_text_commons
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    @Deprecated("Remove this method in future releases")
    private fun migrateFirstDayOfWeek() {
        if (config.migrateFirstDayOfWeek) {
            config.migrateFirstDayOfWeek = false
            config.firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        }
    }

    private fun getTabIndex(tabId: Int): Int {
        return when (tabId) {
            TAB_CLOCK -> TAB_CLOCK_INDEX
            TAB_ALARM -> TAB_ALARM_INDEX
            TAB_STOPWATCH -> TAB_STOPWATCH_INDEX
            TAB_TIMER -> TAB_TIMER_INDEX
            else -> config.lastUsedViewPagerPage
        }
    }
}
