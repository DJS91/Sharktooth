package com.sphy.airconcontroller.fridge

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.ui.DashboardSamples
import com.sphy.airconcontroller.ui.DetailsColumns
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.SidebarSide

/** The same fridge controls embedded in its page or shown over the dashboard. */
class FridgeControlsFragment : AppCompatDialogFragment() {
    private val isPreview get() = arguments?.getBoolean(ARG_PREVIEW) == true
    private var previewStatus: EvakoolProtocol.Status? = null
    private class Card(root: View, val compartment: Int) {
        val label: TextView = root.findViewById(R.id.compartmentLabel)
        val current: TextView = root.findViewById(R.id.compartmentCurrent)
        val target: TextView = root.findViewById(R.id.compartmentTarget)
        val up: Button = root.findViewById(R.id.compartmentUp)
        val down: Button = root.findViewById(R.id.compartmentDown)
    }

    private lateinit var controller: FridgeController
    private lateinit var name: TextView
    private lateinit var subtitle: TextView
    private lateinit var statusText: TextView
    private lateinit var info: TextView
    private lateinit var targetNote: TextView
    private lateinit var graph: FridgeGraphView
    private lateinit var cards: List<Card>
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var power: SwitchMaterial
    private var renderingMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { requireContext().getSharedPreferences("evakool", Context.MODE_PRIVATE) }
    private var rangeMs = FridgeHistory.DAY_MS
    private var graphVersion = -1
    private var graphRange = 0L
    private var graphCelsius = true
    private var actionDialog: AlertDialog? = null
    private var renderPicker: (() -> Unit)? = null
    private val listener: () -> Unit = { render() }
    private val graphTick = object : Runnable {
        override fun run() {
            renderGraph(force = true)
            handler.postDelayed(this, 30_000)
        }
    }
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (controller.client.hasPermissions()) showDevices()
        else Toast.makeText(requireContext(), R.string.fridge_permission_needed, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_OpenDiKey_FridgeControls)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.activity_fridge, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (showsDialog) arrangePopup(view)
        if (isPreview) {
            setupPreview(view, savedInstanceState)
            return
        }
        controller = OpenDiKeyApp.from(requireContext()).fridge
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requireActivity().requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        name = view.findViewById(R.id.fridgeName)
        subtitle = view.findViewById(R.id.fridgeSubtitle)
        statusText = view.findViewById(R.id.fridgeStatus)
        info = view.findViewById(R.id.fridgeInfo)
        targetNote = view.findViewById(R.id.fridgeTargetNote)
        graph = view.findViewById(R.id.fridgeGraph)
        graph.emptyText = getString(R.string.fridge_graph_empty)
        cards = listOf(
            Card(view.findViewById(R.id.fridgeLeftCard), 1),
            Card(view.findViewById(R.id.fridgeRightCard), 2),
        )
        cards[0].label.setText(R.string.fridge_left)
        cards[0].label.setTextColor(requireContext().getColor(R.color.classic_accent))
        cards[1].label.setText(R.string.fridge_right)
        cards[1].label.setTextColor(requireContext().getColor(R.color.gauge_charge))
        cards.forEach { card ->
            val side = card.label.text.toString()
            card.up.contentDescription = getString(R.string.fridge_up, side)
            card.down.contentDescription = getString(R.string.fridge_down, side)
            card.up.setOnClickListener { controller.adjust(card.compartment, +1) }
            card.down.setOnClickListener { controller.adjust(card.compartment, -1) }
        }
        listOf(R.id.fridgeBack, R.id.backButtonEnd).forEach { id ->
            view.findViewById<View>(id).apply {
                if (!showsDialog) visibility = View.GONE
                setOnClickListener { if (showsDialog) dismiss() else requireActivity().finish() }
            }
        }
        modeGroup = view.findViewById(R.id.fridgeMode)
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            // Ignore programmatic updates from render(); only user taps send a command.
            if (!checked || renderingMode) return@addOnButtonCheckedListener
            controller.setMode(id == R.id.fridgeModeMax)
            render()
        }
        power = view.findViewById(R.id.fridgePower)
        power.setOnCheckedChangeListener { _, on ->
            if (renderingMode) return@setOnCheckedChangeListener
            if (on) {
                controller.setPower(true)
                render()
            } else {
                // Switching off can spoil food, so confirm first; cancelling restores the switch.
                actionDialog?.dismiss()
                actionDialog = AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fridge_power_off_title)
                    .setMessage(R.string.fridge_power_off_message)
                    .setPositiveButton(R.string.fridge_power_off_confirm) { _, _ -> controller.setPower(false) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setOnDismissListener { actionDialog = null; render() }
                    .show()
            }
        }
        view.findViewById<Button>(R.id.fridgeReconnect).setOnClickListener { reconnect() }
        view.findViewById<Button>(R.id.fridgeMore).setOnClickListener { showMore() }

        rangeMs = prefs.getLong("graph_range", FridgeHistory.DAY_MS)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.fridgeRange)
        group.check(
            when (rangeMs) {
                FridgeHistory.HOUR_MS -> R.id.fridgeRangeHour
                FridgeHistory.WEEK_MS -> R.id.fridgeRangeWeek
                else -> R.id.fridgeRangeDay
            },
        )
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            rangeMs = when (id) {
                R.id.fridgeRangeHour -> FridgeHistory.HOUR_MS
                R.id.fridgeRangeWeek -> FridgeHistory.WEEK_MS
                else -> FridgeHistory.DAY_MS
            }
            prefs.edit().putLong("graph_range", rangeMs).apply()
            renderGraph(force = true)
        }
    }

    private fun arrangePopup(view: View) {
        val side = AppSettings(requireContext()).sidebarSide
        val row = view.findViewById<LinearLayout>(R.id.fridgeDetailsColumns)
        val controls = view.findViewById<ScrollView>(R.id.fridgeControlsPanel)
        val graphs = view.findViewById<LinearLayout>(R.id.fridgeGraphPanel)
        val ranges = view.findViewById<View>(R.id.fridgeRange)
        graphs.removeView(ranges)
        val controlsContent = controls.getChildAt(0) as LinearLayout
        controlsContent.addView(ranges, 0, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        DetailsColumns.arrange(row, controls, graphs, side)
        view.findViewById<View>(R.id.fridgeBack).visibility = if (side == SidebarSide.LEFT) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.backButtonEnd).visibility = if (side == SidebarSide.RIGHT) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        if (showsDialog) {
            val bounds = resources.displayMetrics
            dialog?.setCanceledOnTouchOutside(true)
            dialog?.window?.setLayout((bounds.widthPixels * 0.94).toInt(), (bounds.heightPixels * 0.92).toInt())
        }
        if (isPreview) return
        controller.addListener(listener)
        controller.attach()
        handler.post(graphTick)
        render()
    }

    override fun onStop() {
        if (isPreview) {
            super.onStop()
            return
        }
        handler.removeCallbacks(graphTick)
        controller.removeListener(listener)
        actionDialog?.setOnDismissListener(null)
        actionDialog?.dismiss()
        actionDialog = null
        controller.client.stopScan()
        controller.client.devicesChanged = null
        renderPicker = null
        controller.detach()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        previewStatus?.let {
            outState.putBoolean(STATE_PREVIEW_ON, it.on)
            outState.putBoolean(STATE_PREVIEW_MAX, it.max)
            outState.putInt(STATE_PREVIEW_LEFT, it.leftTarget)
            outState.putInt(STATE_PREVIEW_RIGHT, it.rightTarget)
            outState.putLong(STATE_PREVIEW_RANGE, rangeMs)
        }
    }

    private fun setupPreview(view: View, savedInstanceState: Bundle?) {
        val sample = DashboardSamples.fridge
        previewStatus = sample.copy(
            on = savedInstanceState?.getBoolean(STATE_PREVIEW_ON, sample.on) ?: sample.on,
            max = savedInstanceState?.getBoolean(STATE_PREVIEW_MAX, sample.max) ?: sample.max,
            leftTarget = savedInstanceState?.getInt(STATE_PREVIEW_LEFT, sample.leftTarget) ?: sample.leftTarget,
            rightTarget = savedInstanceState?.getInt(STATE_PREVIEW_RIGHT, sample.rightTarget) ?: sample.rightTarget,
        )
        rangeMs = savedInstanceState?.getLong(STATE_PREVIEW_RANGE, FridgeHistory.DAY_MS) ?: FridgeHistory.DAY_MS
        val previewCards = listOf(
            Card(view.findViewById(R.id.fridgeLeftCard), 1),
            Card(view.findViewById(R.id.fridgeRightCard), 2),
        )
        val previewPower = view.findViewById<SwitchMaterial>(R.id.fridgePower)
        val previewMode = view.findViewById<MaterialButtonToggleGroup>(R.id.fridgeMode)
        val previewGraph = view.findViewById<FridgeGraphView>(R.id.fridgeGraph)
        val ranges = view.findViewById<MaterialButtonToggleGroup>(R.id.fridgeRange)
        view.findViewById<TextView>(R.id.fridgeName).setText(R.string.fridge_unknown_name)
        view.findViewById<TextView>(R.id.fridgeSubtitle).setText(R.string.fridge_preview_notice)
        view.findViewById<TextView>(R.id.fridgeStatus).setText(R.string.fridge_preview_notice)
        view.findViewById<View>(R.id.fridgeReconnect).visibility = View.GONE
        view.findViewById<View>(R.id.fridgeMore).visibility = View.GONE
        listOf(R.id.fridgeBack, R.id.backButtonEnd).forEach { id ->
            view.findViewById<View>(id).setOnClickListener { dismiss() }
        }
        previewCards[0].label.setText(R.string.fridge_left)
        previewCards[0].label.setTextColor(requireContext().getColor(R.color.classic_accent))
        previewCards[1].label.setText(R.string.fridge_right)
        previewCards[1].label.setTextColor(requireContext().getColor(R.color.gauge_charge))

        fun renderPreview() {
            val status = checkNotNull(previewStatus)
            val unit = if (status.celsius) "C" else "F"
            previewCards.forEach { card ->
                val left = card.compartment == 1
                card.current.text = "${if (left) status.left else status.right}°$unit"
                card.target.text = "${if (left) status.leftTarget else status.rightTarget}°$unit"
            }
            view.findViewById<TextView>(R.id.fridgeInfo).text = getString(R.string.fridge_voltage, status.voltage)
            renderingMode = true
            previewPower.isChecked = status.on
            previewMode.check(if (status.max) R.id.fridgeModeMax else R.id.fridgeModeEco)
            renderingMode = false
            previewPower.setText(if (status.on) R.string.fridge_on else R.string.fridge_off)
            val now = System.currentTimeMillis()
            val samples = listOf(now - rangeMs * 3 / 4, now).map {
                FridgeHistory.Sample(it, status.left, status.right, status.leftTarget, status.rightTarget, status.celsius)
            }
            previewGraph.setData(samples, rangeMs, now, status.celsius)
        }
        previewCards.forEach { card ->
            val side = card.label.text.toString()
            card.up.contentDescription = getString(R.string.fridge_up, side)
            card.down.contentDescription = getString(R.string.fridge_down, side)
            for ((button, delta) in listOf(card.up to 1, card.down to -1)) {
                button.setOnClickListener {
                    val status = checkNotNull(previewStatus)
                    val range = FridgeController.targetRange(status.celsius)
                    previewStatus = if (card.compartment == 1) {
                        status.copy(leftTarget = (status.leftTarget + delta).coerceIn(range))
                    } else status.copy(rightTarget = (status.rightTarget + delta).coerceIn(range))
                    renderPreview()
                }
            }
        }
        previewPower.setOnCheckedChangeListener { _, on ->
            if (!renderingMode) {
                previewStatus = checkNotNull(previewStatus).copy(on = on)
                renderPreview()
            }
        }
        previewMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !renderingMode) {
                previewStatus = checkNotNull(previewStatus).copy(max = id == R.id.fridgeModeMax)
                renderPreview()
            }
        }
        ranges.check(when (rangeMs) {
            FridgeHistory.HOUR_MS -> R.id.fridgeRangeHour
            FridgeHistory.WEEK_MS -> R.id.fridgeRangeWeek
            else -> R.id.fridgeRangeDay
        })
        ranges.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                rangeMs = when (id) {
                    R.id.fridgeRangeHour -> FridgeHistory.HOUR_MS
                    R.id.fridgeRangeWeek -> FridgeHistory.WEEK_MS
                    else -> FridgeHistory.DAY_MS
                }
                renderPreview()
            }
        }
        renderPreview()
    }

    private fun render() {
        val status = controller.status
        val unit = if (status?.celsius != false) "C" else "F"
        name.text = controller.name ?: getString(R.string.fridge_unknown_name)
        subtitle.text = listOfNotNull(controller.transportLabel, controller.address).joinToString(" · ")
        val blocker = controller.controlBlocker
        val pending = controller.pendingCompartment
        cards.forEach { card ->
            val now = status?.let { if (card.compartment == 1) it.left else it.right }
            card.current.text = now?.let { "$it°$unit" } ?: "—"
            card.target.text = controller.displayTarget(card.compartment)?.let { "$it°$unit" } ?: "—"
            card.target.alpha = if (pending == card.compartment) 0.6f else 1f
            val enabled = blocker == null
            card.up.isEnabled = enabled
            card.down.isEnabled = enabled
        }
        info.text = if (status == null) "" else getString(R.string.fridge_voltage, status.voltage)
        renderMode()
        val note = controller.targetNote ?: if (status != null) blocker else null
        targetNote.text = note
        targetNote.visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        statusText.text = controller.message
        renderPicker?.invoke()
        renderGraph(force = false)
    }

    private fun renderMode() {
        val on = controller.displayOn
        renderingMode = true
        power.isChecked = on == true
        renderingMode = false
        power.text = getString(
            when (on) { true -> R.string.fridge_on; false -> R.string.fridge_off; null -> R.string.fridge_power_unknown },
        )
        power.isEnabled = controller.powerBlocker == null && actionDialog == null
        power.alpha = if (controller.pendingOn != null) 0.6f else 1f

        val max = controller.displayMax
        val pendingMax = controller.pendingMax
        val blocker = controller.modeBlocker
        renderingMode = true
        if (max == null) modeGroup.clearChecked()
        else modeGroup.check(if (max) R.id.fridgeModeMax else R.id.fridgeModeEco)
        renderingMode = false
        // Allow taps whenever changes are possible; dim while a change is awaiting confirmation.
        val enabled = blocker == null
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        modeGroup.alpha = if (pendingMax != null) 0.6f else 1f
    }

    private fun renderGraph(force: Boolean) {
        val history = controller.history
        val celsius = controller.status?.celsius ?: history.samples.lastOrNull()?.celsius ?: true
        if (!force && history.version == graphVersion && graphRange == rangeMs && graphCelsius == celsius) return
        val now = System.currentTimeMillis()
        graphVersion = history.version
        graphRange = rangeMs
        graphCelsius = celsius
        graph.setData(history.since(rangeMs, now), rangeMs, now, celsius)
    }

    private fun reconnect() {
        val client = controller.client
        if (!controller.useUsb && controller.usb.safeForAndroid &&
            (!client.hasPermissions() || client.selectedAddress == null)
        ) chooseDevice()
        else controller.reconnect()
    }

    private fun showMore() {
        val actions = mutableListOf(R.string.fridge_details)
        if (!controller.useUsb) actions.addAll(listOf(R.string.fridge_choose, R.string.fridge_forget))
        actions.add(if (controller.useUsb) R.string.fridge_use_android else R.string.fridge_use_usb)
        actions.add(R.string.fridge_clear_history)
        actionDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.fridge_title)
            .setItems(actions.map(::getString).toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.fridge_details -> showDetails()
                    R.string.fridge_choose -> chooseDevice()
                    R.string.fridge_forget -> controller.client.forget()
                    R.string.fridge_use_android -> controller.switchTransport(false)
                    R.string.fridge_use_usb -> controller.switchTransport(true)
                    R.string.fridge_clear_history -> confirmClearHistory()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearHistory() {
        actionDialog = AlertDialog.Builder(requireContext())
            .setMessage(R.string.fridge_clear_history_confirm)
            .setPositiveButton(R.string.fridge_clear_history) { _, _ ->
                controller.history.clear()
                renderGraph(force = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetails() {
        actionDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.fridge_details)
            .setMessage(getString(
                R.string.fridge_details_text,
                listOfNotNull(controller.transportLabel, controller.name).joinToString(" · "),
                controller.address.orEmpty(),
                controller.message,
                controller.lastFrame ?: getString(R.string.fridge_no_readings),
            ))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseDevice() {
        if (controller.useUsb || !controller.usb.safeForAndroid) {
            Toast.makeText(requireContext(), R.string.fridge_android_wait_off, Toast.LENGTH_LONG).show()
            return
        }
        val client = controller.client
        if (!client.hasPermissions()) permissions.launch(client.requiredPermissions())
        else showDevices()
    }

    private fun showDevices() {
        val client = controller.client
        if (controller.useUsb || !controller.usb.safeForAndroid) {
            Toast.makeText(requireContext(), R.string.fridge_android_wait_off, Toast.LENGTH_LONG).show()
            return
        }
        actionDialog?.dismiss()
        var devices = emptyList<EvakoolClient.Device>()
        var visibleDevices = emptyList<EvakoolClient.Device>()
        val view = layoutInflater.inflate(R.layout.dialog_fridge_devices, null)
        val scanStatus = view.findViewById<TextView>(R.id.fridgeScanStatus)
        val progress = view.findViewById<View>(R.id.fridgeScanProgress)
        val showAll = view.findViewById<CheckBox>(R.id.fridgeShowAllDevices)
        val list = view.findViewById<ListView>(R.id.fridgeDeviceList)
        val empty = view.findViewById<TextView>(R.id.fridgeScanEmpty)
        val adapter = ArrayAdapter<String>(requireContext(), R.layout.item_fridge_device)
        list.adapter = adapter
        list.emptyView = empty
        val picker = AlertDialog.Builder(requireContext())
            .setView(view)
            .setPositiveButton(R.string.fridge_scan_again, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        actionDialog = picker
        renderPicker = {
            visibleDevices = devices.filter { showAll.isChecked || it.recommended }
                .sortedByDescending { it.recommended }
            adapter.clear()
            adapter.addAll(visibleDevices.map { device ->
                getString(
                    if (device.recommended) R.string.fridge_device_recommended else R.string.fridge_device_other,
                    device.name, device.address,
                )
            })
            scanStatus.text = getString(R.string.fridge_scan_summary, client.message, devices.size, visibleDevices.size)
            progress.visibility = if (client.scanning) View.VISIBLE else View.GONE
            empty.setText(
                when {
                    devices.isNotEmpty() && visibleDevices.isEmpty() -> R.string.fridge_scan_hidden
                    client.scanning -> R.string.fridge_scan_empty
                    else -> R.string.fridge_scan_empty_finished
                },
            )
            picker.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !client.scanning
        }
        client.devicesChanged = {
            devices = it
            renderPicker?.invoke()
        }
        showAll.setOnCheckedChangeListener { _, _ -> renderPicker?.invoke() }
        list.setOnItemClickListener { _, _, position, _ ->
            val device = visibleDevices[position]
            // End the scan before connecting; dismiss must not start a second connection.
            picker.setOnDismissListener(null)
            client.devicesChanged = null
            renderPicker = null
            picker.dismiss()
            client.select(device)
        }
        picker.setOnDismissListener {
            client.stopScan()
            client.devicesChanged = null
            renderPicker = null
            if (!controller.useUsb && controller.usb.safeForAndroid && client.selectedAddress != null) client.start()
        }
        picker.show()
        picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { client.scan() }
        client.scan()
        renderPicker?.invoke()
    }

    companion object {
        private const val ARG_PREVIEW = "preview"
        private const val STATE_PREVIEW_ON = "preview_on"
        private const val STATE_PREVIEW_MAX = "preview_max"
        private const val STATE_PREVIEW_LEFT = "preview_left"
        private const val STATE_PREVIEW_RIGHT = "preview_right"
        private const val STATE_PREVIEW_RANGE = "preview_range"

        internal fun preview() = FridgeControlsFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_PREVIEW, true) }
        }
    }
}
