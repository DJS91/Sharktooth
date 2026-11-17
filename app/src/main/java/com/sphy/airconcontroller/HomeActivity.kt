package com.sphy.airconcontroller

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.apps.ProjectionApps
import com.sphy.airconcontroller.apps.ProjectionAutoSwitch
import com.sphy.airconcontroller.boot.AdasAutoApplyCoordinator
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.usb.DiKeyUsbBridge
import com.sphy.airconcontroller.fridge.FridgeTile
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun controlsBarAvailable(isDebug: Boolean, state: DiKeySession.ConnectionState): Boolean =
    isDebug || state == DiKeySession.ConnectionState.CONNECTED

open class HomeActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var fridgeTile: FridgeTile
    private lateinit var connStatus: android.widget.TextView
    private lateinit var connDot: android.view.View
    private var dotPulse: ObjectAnimator? = null

    /** The running automatic switch to CarPlay, if any; a touch anywhere cancels it. */
    private var autoSwitchCountdown: Job? = null
    private var swallowingCancelTouch = false
    private lateinit var adasIcon: android.widget.ImageView
    private var animatePageOnResume = false
    private var selectedNavigationItemId: Int? = null
    private var slidingNavigationHighlight = false

    private val adasHoldHandler = Handler(Looper.getMainLooper())
    private var adasHoldFired = false
    private val adasHoldRunnable = Runnable {
        adasHoldFired = true
        showAdasAutoApplyDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = OpenDiKeyApp.from(this).dikey
        animatePageOnResume = intent.getBooleanExtra(EXTRA_PAGE_TRANSITION, false)
        intent.removeExtra(EXTRA_PAGE_TRANSITION)
    }

    override fun setContentView(layoutResID: Int) {
        val page = layoutInflater.inflate(layoutResID, null)
        val shell = layoutInflater.inflate(R.layout.activity_main, null)
        listOf(
            R.id.colorBackButton, R.id.mappingBackButton,
            R.id.adasBackButton, R.id.sentryBackButton, R.id.petBackButton,
            R.id.fridgeBack, R.id.backButtonEnd,
        ).forEach { page.findViewById<View>(it)?.visibility = View.GONE }
        shell.findViewById<FrameLayout>(R.id.homePageContainer).addView(
            page,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        super.setContentView(shell)
        setupHome()
    }

    private fun setupHome() {
        fridgeTile = FridgeTile(this)

        connStatus = findViewById(R.id.mainConnStatus)
        connDot = findViewById(R.id.mainConnDot)
        adasIcon = findViewById(R.id.homeAdasIcon)
        val titleText = getString(R.string.hub_title)
        val toothStart = titleText.indexOf("TOOTH")
        check(toothStart >= 0) { "hub_title must contain TOOTH" }
        findViewById<android.widget.TextView>(R.id.homeTitle).text = SpannableString(titleText).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@HomeActivity, R.color.accent_orange)),
                toothStart,
                titleText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        findViewById<android.widget.TextView>(R.id.homeVersionText).text = AppUpdater.currentVersionName(this)

        findViewById<android.view.View>(R.id.homeControlsBarButton).setOnClickListener {
            navigateTo(ColorConfigActivity::class.java)
        }
        if (this is ColorConfigActivity || this is ButtonMappingActivity) {
            val tabs = findViewById<MaterialButtonToggleGroup>(R.id.controlsBarTabs)
            tabs.check(if (this is ColorConfigActivity) R.id.controlsBarColours else R.id.controlsBarButtons)
            tabs.addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    when (id) {
                        R.id.controlsBarColours -> navigateTo(ColorConfigActivity::class.java)
                        R.id.controlsBarButtons -> navigateTo(ButtonMappingActivity::class.java)
                    }
                }
            }
        }
        findViewById<android.view.View>(R.id.homeVehicleInfoButton).setOnClickListener {
            navigateTo(MainActivity::class.java)
        }
        findViewById<android.view.View>(R.id.homeAdasButton).let { adasButton ->
            adasButton.setOnClickListener {
                navigateTo(AdasActivity::class.java)
            }
            adasButton.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        adasHoldFired = false
                        adasHoldHandler.postDelayed(adasHoldRunnable, ADAS_HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        adasHoldHandler.removeCallbacks(adasHoldRunnable)
                        if (!adasHoldFired) v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        adasHoldHandler.removeCallbacks(adasHoldRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
        findViewById<android.view.View>(R.id.homeSentryButton).setOnClickListener {
            navigateTo(SentryActivity::class.java)
        }
        findViewById<android.view.View>(R.id.homeModesButton).setOnClickListener {
            navigateTo(CampModeActivity::class.java)
        }
        if (this is PetModeActivity || this is CampModeActivity) {
            val tabs = findViewById<MaterialButtonToggleGroup>(R.id.modesTabs)
            tabs.check(if (this is PetModeActivity) R.id.modesPet else R.id.modesCamp)
            tabs.addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    when (id) {
                        R.id.modesPet -> navigateTo(PetModeActivity::class.java)
                        R.id.modesCamp -> navigateTo(CampModeActivity::class.java)
                    }
                }
            }
        }
        findViewById<android.widget.ImageButton>(R.id.mainDebugButton).setOnClickListener {
            leavePage { startActivity(Intent(this, DebugHubActivity::class.java)) }
        }
        findViewById<android.widget.ImageButton>(R.id.mainSettingsButton).setOnClickListener {
            leavePage { startActivity(Intent(this, SettingsHubActivity::class.java)) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    session.connectionState.collect { state ->
                        renderConnectionState(state)
                    }
                } finally {
                    // Don't leave the pulse running while the screen is stopped.
                    stopDotPulse()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.usbBridge.wifi.collect { wifi ->
                    findViewById<android.view.View>(R.id.mainWifiRow).visibility =
                        if (wifi.state == DiKeyUsbBridge.WifiState.CONNECTED) android.view.View.VISIBLE
                        else android.view.View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AdasAutoApplyCoordinator.succeeded.collect { succeeded ->
                    val color = if (succeeded) R.color.tyre_ok else R.color.accent_orange
                    adasIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@HomeActivity, color))
                }
            }
        }

        setUpProjectionShortcut()
        selectedNavigationItemId = when (this) {
            is VehicleInfoActivity -> R.id.homeVehicleInfoButton
            is ColorConfigActivity, is ButtonMappingActivity -> R.id.homeControlsBarButton
            is AdasActivity -> R.id.homeAdasButton
            is SentryActivity -> R.id.homeSentryButton
            is PetModeActivity, is CampModeActivity -> R.id.homeModesButton
            is com.sphy.airconcontroller.fridge.FridgeActivity -> R.id.homeFridgeButton
            else -> null
        }
        selectedNavigationItemId?.let { findViewById<View>(it).isSelected = true }
        findViewById<View>(R.id.homeSidebar).viewTreeObserver.addOnPreDrawListener {
            if (!slidingNavigationHighlight) updateNavigationHighlight()
            true
        }
        if (this is MainActivity) {
            maybePromptHiddenApiConsent()
            startAdbSetupIfNeeded()
            handleLaunchIntent(intent)
            maybeRequestBluetoothPermissions()
        }
    }

    internal fun renderConnectionState(state: DiKeySession.ConnectionState) {
        connStatus.setText(
            when (state) {
                DiKeySession.ConnectionState.CONNECTED -> R.string.dikey_state_connected
                DiKeySession.ConnectionState.CONNECTING -> R.string.dikey_state_connecting
                DiKeySession.ConnectionState.DISCONNECTED -> R.string.dikey_state_disconnected
            },
        )
        findViewById<View>(R.id.homeControlsBarButton).visibility =
            if (controlsBarAvailable(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, state)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        showConnectionDot(state)
    }

    fun navigateTo(destination: Class<out HomeActivity>) {
        if (destination.isInstance(this)) {
            if (slidingNavigationHighlight) {
                slideNavigationHighlight(destination) { slidingNavigationHighlight = false }
            }
            return
        }
        leavePage {
            slideNavigationHighlight(destination) { openPage(destination) }
        }
    }

    private fun openPage(destination: Class<out HomeActivity>) {
        val intent = Intent(this, destination).putExtra(EXTRA_PAGE_TRANSITION, true)
        if (destination == MainActivity::class.java) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        if (this !is MainActivity) {
            finish()
            // CLEAR_TOP can use the finishing activity's close animation instead of the launch options.
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun updateNavigationHighlight() {
        val sidebar = findViewById<FrameLayout>(R.id.homeSidebar)
        val highlight = findViewById<View>(R.id.homeNavigationHighlight)
        val tile = selectedNavigationItemId?.let { findViewById<View>(it) }
        val visibleBounds = Rect()
        if (tile == null || !tile.isShown || !tile.getLocalVisibleRect(visibleBounds)) {
            highlight.visibility = View.INVISIBLE
            return
        }
        val bounds = Rect()
        tile.getDrawingRect(bounds)
        sidebar.offsetDescendantRectToMyCoords(tile, bounds)
        if (highlight.layoutParams.width != tile.width || highlight.layoutParams.height != tile.height) {
            highlight.layoutParams = FrameLayout.LayoutParams(tile.width, tile.height)
        }
        highlight.x = bounds.left.toFloat()
        highlight.y = bounds.top.toFloat()
        highlight.clipBounds = visibleBounds
        highlight.visibility = View.VISIBLE
    }

    private fun slideNavigationHighlight(destination: Class<out HomeActivity>, onComplete: () -> Unit) {
        val targetId = when (destination) {
            MainActivity::class.java -> R.id.homeVehicleInfoButton
            ColorConfigActivity::class.java, ButtonMappingActivity::class.java -> R.id.homeControlsBarButton
            AdasActivity::class.java -> R.id.homeAdasButton
            SentryActivity::class.java -> R.id.homeSentryButton
            CampModeActivity::class.java, PetModeActivity::class.java -> R.id.homeModesButton
            com.sphy.airconcontroller.fridge.FridgeActivity::class.java -> R.id.homeFridgeButton
            else -> null
        }
        val highlight = findViewById<View>(R.id.homeNavigationHighlight)
        val target = targetId?.let { findViewById<View>(it) }
        val wasSliding = slidingNavigationHighlight
        highlight.animate().cancel()
        slidingNavigationHighlight = false
        if ((targetId == selectedNavigationItemId && !wasSliding) || target == null || !target.isShown ||
            highlight.visibility != View.VISIBLE || !ValueAnimator.areAnimatorsEnabled()
        ) {
            onComplete()
            return
        }
        val bounds = Rect()
        target.getDrawingRect(bounds)
        findViewById<FrameLayout>(R.id.homeSidebar).offsetDescendantRectToMyCoords(target, bounds)
        slidingNavigationHighlight = true
        highlight.clipBounds = null
        highlight.animate()
            .y(bounds.top.toFloat())
            .setDuration(NAVIGATION_SLIDE_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                // Keep the highlight at its destination until the new activity replaces this one.
                onComplete()
            }
            .start()
    }

    override fun onPause() {
        findViewById<View>(R.id.homeNavigationHighlight).animate().cancel()
        slidingNavigationHighlight = false
        super.onPause()
    }

    protected open fun leavePage(action: () -> Unit) = action()

    override fun onResume() {
        super.onResume()
        if (animatePageOnResume) {
            animatePageOnResume = false
            val page = findViewById<FrameLayout>(R.id.homePageContainer).getChildAt(0)
            page.animate().cancel()
            page.alpha = 0f
            page.animate().alpha(1f).setDuration(PAGE_TRANSITION_MS).start()
        }
    }

    override fun onStart() {
        super.onStart()
        if (animatePageOnResume) {
            preparePageTransition()
        }
        fridgeTile.start()
        if (this is MainActivity) {
            session.ensureConnected()
            // Retry after boot if the vehicle services were not ready when the process started.
            AdasAutoApplyCoordinator.ensureApplied(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        animatePageOnResume = intent.getBooleanExtra(EXTRA_PAGE_TRANSITION, false)
        intent.removeExtra(EXTRA_PAGE_TRANSITION)
        if (animatePageOnResume) {
            preparePageTransition()
        }
        handleLaunchIntent(intent)
    }

    private fun preparePageTransition() {
        val page = findViewById<FrameLayout>(R.id.homePageContainer).getChildAt(0)
        page.animate().cancel()
        page.alpha = 0f
    }

    private fun showConnectionDot(state: DiKeySession.ConnectionState) {
        val color = when (state) {
            DiKeySession.ConnectionState.CONNECTED -> R.color.tyre_ok
            DiKeySession.ConnectionState.CONNECTING -> R.color.status_warning
            DiKeySession.ConnectionState.DISCONNECTED -> R.color.text_muted
        }
        connDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        if (state == DiKeySession.ConnectionState.CONNECTING) startDotPulse() else stopDotPulse()
    }

    private fun startDotPulse() {
        if (dotPulse?.isRunning == true) return
        dotPulse = ObjectAnimator.ofFloat(connDot, android.view.View.ALPHA, 1f, DOT_PULSE_MIN_ALPHA).apply {
            duration = DOT_PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopDotPulse() {
        dotPulse?.cancel()
        dotPulse = null
        connDot.alpha = 1f
    }

    /**
     * Shows a shortcut to the car's CarPlay / Android Auto app, but only while a phone is projecting
     * (see [ProjectionApps.isPhoneProjecting]). Re-checked every few seconds while this screen is visible.
     *
     * With "Switch to CarPlay automatically" on, it also starts a short countdown and then opens that app,
     * once, when a phone is projecting and the ADAS profile is applied (see [ProjectionAutoSwitch]).
     * Touching the screen during the countdown cancels it (see [dispatchTouchEvent]).
     */
    private fun setUpProjectionShortcut() {
        val tile = findViewById<android.view.View>(R.id.homeProjectionButton)
        val icon = findViewById<android.widget.ImageView>(R.id.homeProjectionIcon)
        val title = findViewById<android.widget.TextView>(R.id.homeProjectionTitle)
        var shown: ProjectionApps.Entry? = null

        tile.setOnClickListener {
            val entry = shown ?: return@setOnClickListener
            openProjection(entry)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Children of this scope are cancelled when the screen stops, which also cancels a countdown.
                val scope = this
                while (true) {
                    val (entry, projecting) = withContext(Dispatchers.IO) {
                        val app = runCatching { ProjectionApps.find(this@HomeActivity) }.getOrNull()
                        val active = if (app == null) false else runCatching { ProjectionApps.isPhoneProjecting(this@HomeActivity) }.getOrNull()
                        app to active
                    }
                    // null means "couldn't tell": leave the tile as it is rather than flicker it.
                    if (projecting != null) {
                        val target = if (projecting) entry else null
                        if (target?.packageName != shown?.packageName) {
                            if (target != null) {
                                icon.setImageDrawable(target.icon)
                                title.text = target.label
                                tile.contentDescription = target.label
                            }
                            // INVISIBLE, not GONE: the slot stays reserved so the other tiles keep their size.
                            tile.visibility = if (target != null) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        }
                        shown = target
                    }

                    if (this@HomeActivity is MainActivity && AppSettings(this@HomeActivity).autoSwitchToCarPlay) {
                        // With ADAS auto-apply off there is no profile to wait for, so don't wait on it.
                        val adasApplied = !AdasAutoApplyCoordinator.isAutoApplyActive(this@HomeActivity) ||
                            AdasAutoApplyCoordinator.succeeded.value
                        if (autoSwitchCountdown?.isActive == true && ProjectionAutoSwitch.shouldAbort(projecting, adasApplied)) {
                            autoSwitchCountdown?.cancel()
                        }
                        if (entry != null && ProjectionAutoSwitch.shouldStart(projecting, adasApplied)) {
                            autoSwitchCountdown = scope.launch { runAutoSwitchCountdown(entry) }
                        }
                    } else {
                        autoSwitchCountdown?.cancel()
                    }
                    delay(PROJECTION_POLL_MS)
                }
            }
        }
    }

    /**
     * Counts down on the tile's subtitle ("Launching in 3", "Launching in 2", "Launching in 1") and then opens [entry]. Cancelling the
     * coroutine (a touch, the phone disconnecting, leaving the screen) restores the subtitle.
     */
    private suspend fun runAutoSwitchCountdown(entry: ProjectionApps.Entry) {
        val subtitle = findViewById<android.widget.TextView>(R.id.homeProjectionSubtitle)
        val title = findViewById<View>(R.id.homeProjectionTitle)
        val normalText = getString(R.string.projection_home_subtitle)
        try {
            title.visibility = View.GONE
            subtitle.visibility = View.VISIBLE
            for (second in AUTO_SWITCH_COUNTDOWN_S downTo 1) {
                subtitle.text = getString(R.string.projection_auto_launching, second)
                delay(1_000L)
            }
            openProjection(entry)
        } finally {
            subtitle.text = normalText
            subtitle.visibility = View.GONE
            title.visibility = View.VISIBLE
        }
    }

    private fun openProjection(entry: ProjectionApps.Entry) {
        leavePage {
            runCatching { startActivity(entry.launchIntent) }.onFailure {
                Toast.makeText(this, getString(R.string.projection_open_failed, entry.label), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Any touch while the automatic switch is counting down cancels it. That touch is swallowed, so a
     * stray tap meant to stop the countdown can't also press a tile underneath.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (consumeCampWakeTouch(ev)) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && autoSwitchCountdown?.isActive == true) {
            autoSwitchCountdown?.cancel()
            swallowingCancelTouch = true
            return true
        }
        if (swallowingCancelTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowingCancelTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        fridgeTile.stop()
        super.onStop()
    }

    override fun onDestroy() {
        adasHoldHandler.removeCallbacks(adasHoldRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            session.ensureConnected()
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbPermissionReceiver.ACTION -> session.onUsbPermissionResult(intent)
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = deviceExtra(intent) ?: return
                session.tryConnectUsb(device)
            }
        }
    }

    private fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun maybeRequestBluetoothPermissions() {
        if (session.isConnected || session.isUsbOpen) return
        if (session.hasBluetoothPermissions()) return
        ActivityCompat.requestPermissions(
            this,
            session.requiredBluetoothPermissions(),
            BT_PERMISSION_REQUEST
        )
    }

    private fun showAdasAutoApplyDialog() {
        val settings = AppSettings(this)
        val view = layoutInflater.inflate(R.layout.dialog_adas_auto_apply, null)
        val toggle = view.findViewById<SwitchMaterial>(R.id.adasAutoApplyDialogSwitch)
        toggle.isChecked = settings.adasApplyOnBoot
        AlertDialog.Builder(this)
            .setTitle(R.string.adas_auto_apply_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.adas_auto_apply_save) { _, _ ->
                settings.adasApplyOnBoot = toggle.isChecked
            }
            .setNegativeButton(R.string.adas_auto_apply_cancel, null)
            .show()
    }

    private fun startAdbSetupIfNeeded() {
        lifecycleScope.launch {
            AdbPermissionManager.ensureVehicleApiAccess(this@HomeActivity)
            if (!AdbPermissionManager.isSetupComplete(this@HomeActivity)) {
                AdbPermissionManager.runSetup(this@HomeActivity)
            }
        }
    }

    private fun maybePromptHiddenApiConsent() {
        if (AdbPermissionManager.hasHiddenApiConsent(this) ||
            AdbPermissionManager.hasBeenPromptedForHiddenApi(this)
        ) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_api_title)
            .setMessage(R.string.hidden_api_body)
            .setCancelable(false)
            .setPositiveButton(R.string.hidden_api_allow) { _, _ ->
                AdbPermissionManager.setHiddenApiConsent(this, true)
                AdbPermissionManager.markHiddenApiPrompted(this)
                lifecycleScope.launch {
                    val applied = AdbPermissionManager.ensureVehicleApiAccess(this@HomeActivity)
                    if (applied) AdbPermissionManager.restartApp(this@HomeActivity)
                }
            }
            .setNegativeButton(R.string.hidden_api_not_now) { _, _ ->
                AdbPermissionManager.markHiddenApiPrompted(this)
            }
            .show()
    }

    companion object {
        private const val EXTRA_PAGE_TRANSITION = "com.sphy.airconcontroller.PAGE_TRANSITION"
        private const val PAGE_TRANSITION_MS = 180L
        private const val NAVIGATION_SLIDE_MS = 280L
        private const val BT_PERMISSION_REQUEST = 1001
        private const val ADAS_HOLD_MS = 5_000L
        private const val DOT_PULSE_MS = 800L
        private const val PROJECTION_POLL_MS = 4_000L
        private const val AUTO_SWITCH_COUNTDOWN_S = 3
        private const val DOT_PULSE_MIN_ALPHA = 0.2f
    }
}
