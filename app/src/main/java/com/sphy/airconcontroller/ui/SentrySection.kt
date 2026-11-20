package com.sphy.airconcontroller.ui

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.update.AppUpdater
import com.sphy.airconcontroller.update.CompanionAppInstaller
import com.sphy.airconcontroller.update.CompanionApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compact recording-app controls at the bottom of Settings. */
class SentrySection(private val activity: AppCompatActivity, root: View) : DefaultLifecycleObserver {
    private val panels: List<CompanionPanel>
    private var detailsDialog: AlertDialog? = null

    private val installLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            panels.forEach {
                it.refresh()
                it.watchForPackageChange()
            }
        }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            panels.filter { it.installer.packageName == pkg }.forEach { it.refresh() }
        }
    }

    init {
        panels = listOf(
            CompanionPanel(
                root = root.findViewById(R.id.strikePanel),
                installer = CompanionApps.strike,
                fallbackIcon = R.drawable.ic_strike,
                badge = R.string.strike_badge,
                badgeColor = R.color.accent_orange,
                badgeBackground = R.color.accent_orange_soft,
                installTitle = R.string.strike_install_title,
                installedTitle = R.string.strike_title,
                installBody = R.string.strike_install_body,
                installedBody = R.string.strike_installed_body,
                openButtonText = R.string.strike_open_button,
                openFailed = R.string.strike_open_failed,
            ),
        )

        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(packageChangeReceiver, filter)
        }
        panels.forEach { it.refresh() }
    }

    override fun onResume(owner: LifecycleOwner) {
        panels.forEach { it.refresh() }
    }

    override fun onStop(owner: LifecycleOwner) {
        detailsDialog?.dismiss()
        detailsDialog = null
        panels.forEach { it.cancelWatch() }
        try {
            activity.unregisterReceiver(packageChangeReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private suspend fun ensureInstallAllowed(
        onBlocked: (String) -> Unit,
        onWarning: (String) -> Unit,
    ): Boolean {
        if (AppUpdater.canInstallPackages(activity)) return true

        val pkg = activity.packageName
        AdbPermissionManager.runShellBatch(
            activity,
            listOf(
                "appops set ${AdbPermissionManager.userFlag()}$pkg REQUEST_INSTALL_PACKAGES allow",
                "cmd appops set ${AdbPermissionManager.userFlag()}$pkg REQUEST_INSTALL_PACKAGES allow",
            ),
        )
        if (AppUpdater.canInstallPackages(activity)) return true

        val opened = withContext(Dispatchers.Main) {
            AppUpdater.openInstallPermissionSettings(activity)
        }
        withContext(Dispatchers.Main) {
            if (opened) {
                onBlocked(activity.getString(R.string.settings_updates_need_permission))
            } else {
                onWarning(activity.getString(R.string.settings_updates_install_anyway))
            }
        }
        return !opened
    }

    private inner class CompanionPanel(
        root: View,
        val installer: CompanionAppInstaller,
        @DrawableRes private val fallbackIcon: Int,
        @StringRes badge: Int,
        @ColorRes badgeColor: Int,
        @ColorRes badgeBackground: Int,
        @StringRes private val installTitle: Int,
        @StringRes private val installedTitle: Int,
        @StringRes private val installBody: Int,
        @StringRes private val installedBody: Int,
        @StringRes private val caveatsTitle: Int? = null,
        @StringRes private val caveats: Int? = null,
        @StringRes openButtonText: Int,
        @StringRes private val openFailed: Int,
    ) {
        private val icon: ImageView = root.findViewById(R.id.companionIcon)
        private val headline: TextView = root.findViewById(R.id.companionHeadline)
        private val statusText: TextView = root.findViewById(R.id.companionStatus)
        private val progress: ProgressBar = root.findViewById(R.id.companionProgress)
        private val openButton: Button = root.findViewById(R.id.companionOpenButton)
        private val installButton: Button = root.findViewById(R.id.companionInstallButton)
        private var busy = false
        private var watchJob: Job? = null

        init {
            root.findViewById<TextView>(R.id.companionBadge).apply {
                setText(badge)
                setTextColor(activity.getColor(badgeColor))
                backgroundTintList = ColorStateList.valueOf(activity.getColor(badgeBackground))
            }
            root.findViewById<Button>(R.id.companionDetailsButton).setOnClickListener {
                val installed = installer.isInstalled(activity)
                val message = listOfNotNull(
                    activity.getString(if (installed) installedBody else installBody),
                    caveatsTitle?.let { activity.getString(it) },
                    caveats?.let { activity.getString(it) },
                ).joinToString("\n\n")
                detailsDialog?.dismiss()
                detailsDialog = AlertDialog.Builder(activity)
                    .setTitle(if (installed) installedTitle else installTitle)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            openButton.setText(openButtonText)
            openButton.setOnClickListener { open() }
            installButton.setOnClickListener { downloadAndInstall() }
        }

        fun refresh() {
            val installed = installer.isInstalled(activity)
            openButton.visibility = if (installed) View.VISIBLE else View.GONE
            val appIcon = if (installed) installer.appIcon(activity) else null
            if (appIcon != null) icon.setImageDrawable(appIcon) else icon.setImageResource(fallbackIcon)
            if (installed) {
                headline.setText(installedTitle)
                installButton.setText(R.string.sentry_reinstall_button)
                if (!busy) {
                    val version = installer.installedVersionName(activity)
                    statusText.text = if (version != null) {
                        activity.getString(R.string.companion_installed_status, version)
                    } else {
                        activity.getString(R.string.sentry_installed_status)
                    }
                }
            } else {
                headline.setText(installTitle)
                installButton.setText(R.string.sentry_install_button)
                if (!busy) {
                    statusText.setText(R.string.sentry_install_idle)
                }
            }
        }

        /** PackageManager can lag briefly after the installer returns. */
        fun watchForPackageChange() {
            watchJob?.cancel()
            watchJob = activity.lifecycleScope.launch {
                repeat(8) {
                    delay(750)
                    refresh()
                    if (installer.isInstalled(activity)) return@launch
                }
            }
        }

        fun cancelWatch() {
            watchJob?.cancel()
            watchJob = null
        }

        private fun open() {
            val launch = installer.launchIntent(activity)
            if (launch == null) {
                Toast.makeText(activity, openFailed, Toast.LENGTH_SHORT).show()
                refresh()
                return
            }
            try {
                activity.startActivity(launch)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, openFailed, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }

        private fun downloadAndInstall() {
            if (busy) return
            setBusy(true, activity.getString(R.string.settings_updates_checking))
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            progress.progress = 0

            activity.lifecycleScope.launch {
                try {
                    val allowed = ensureInstallAllowed(
                        onBlocked = { setBusy(false, it) },
                        onWarning = { statusText.text = it },
                    )
                    if (!allowed) return@launch
                    val release = installer.fetchLatestRelease()
                    withContext(Dispatchers.Main) {
                        statusText.text = activity.getString(
                            R.string.sentry_install_found,
                            release.versionName,
                        )
                    }
                    val dest = installer.cacheFile(activity)
                    AppUpdater.downloadApk(release.apkUrl, dest) { downloaded, total ->
                        activity.lifecycleScope.launch(Dispatchers.Main) {
                            if (total > 0L) {
                                progress.isIndeterminate = false
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                progress.progress = pct
                                statusText.text = activity.getString(R.string.settings_updates_downloading, pct)
                            } else {
                                progress.isIndeterminate = true
                                statusText.text = activity.getString(
                                    R.string.settings_updates_downloading_bytes,
                                    downloaded / 1024L,
                                )
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        setBusy(false, activity.getString(R.string.settings_updates_installing))
                        try {
                            installLauncher.launch(
                                AppUpdater.installApkIntent(activity, dest),
                            )
                            watchForPackageChange()
                        } catch (e: ActivityNotFoundException) {
                            setBusy(
                                false,
                                activity.getString(
                                    R.string.settings_updates_failed,
                                    e.message ?: e.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        setBusy(
                            false,
                            activity.getString(
                                R.string.settings_updates_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                        )
                        Toast.makeText(
                            activity,
                            R.string.settings_updates_signature_hint,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        private fun setBusy(value: Boolean, status: String) {
            busy = value
            installButton.isEnabled = !value
            openButton.isEnabled = !value
            statusText.text = status
            if (!value) progress.visibility = View.GONE
        }
    }
}
