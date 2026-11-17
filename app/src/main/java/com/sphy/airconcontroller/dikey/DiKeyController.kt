package com.sphy.airconcontroller.dikey

import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class DialDisplay(val displayType: Int, val value: Int)

data class DialDisplays(val left: DialDisplay? = null, val right: DialDisplay? = null)

/** One GATT write in flight. USB bridge and native BLE both implement this. */
interface DiKeyFrameSink {
    fun writeFrame(bytes: ByteArray): Boolean
}

/**
 * DiKey session: write queue, 0x08/0x06/0x07 setup, dial/LED restore.
 * Transport (phone GATT or C6 USB bridge) is a [DiKeyFrameSink].
 */
class DiKeyController(
    private val onStatus: (String) -> Unit,
    private val onEvent: (DiKeyEvent) -> Unit,
    private val onDialsReady: () -> Unit = {},
    private val onLinkDown: () -> Unit = {}
) {
    private val writeQueue = ArrayDeque<OutboundWrite>()
    private var writeInFlight = false
    private var inFlight: OutboundWrite? = null
    private var sink: DiKeyFrameSink? = null
    private var ready = false

    private var lastDialTypeLeft: Int? = null
    private var lastDialValueLeft: Int? = null
    private var lastDialTypeRight: Int? = null
    private var lastDialValueRight: Int? = null
    private var leftNotifyRevision = 0L
    private var rightNotifyRevision = 0L
    private val _dialDisplays = MutableStateFlow(DialDisplays())
    val dialDisplays: StateFlow<DialDisplays> = _dialDisplays.asStateFlow()
    private var rangeConfigured = false
    private var modesConfigured = false
    private var pendingRestore: PendingDialRestore? = null
    private var pendingLed: com.sphy.airconcontroller.storage.LedRestoreSnapshot? = null

    val isConnected: Boolean get() = ready
    @Volatile var lightingSuppressed = false
        private set

    fun seedLightingSuppression(suppressed: Boolean) {
        lightingSuppressed = suppressed
    }

    suspend fun applyCampLighting(enabled: Boolean, snapshot: LedRestoreSnapshot): Boolean {
        val keys = checkNotNull(snapshot.backlight) { "Camp lighting requires the saved key-backlight colour." }
        if (!ready) {
            onStatus("Camp lighting failed: DiKey disconnected")
            return false
        }
        lightingSuppressed = !enabled
        val lighting = snapshot.copy(bars = snapshot.bars.mapValues { (_, state) ->
            if (enabled && state.mode == LedMode.OFF.code) state.copy(mode = LedMode.ON.code) else state
        })
        seedLedMemory(lighting)
        val writes = mutableListOf<CompletableDeferred<Boolean>>()
        fun send(frame: ByteArray) {
            val completion = CompletableDeferred<Boolean>()
            writes += completion
            if (!enqueue(OutboundWrite(frame, onComplete = { completion.complete(it) }))) completion.complete(false)
        }
        for ((bar, state) in lighting.bars) {
            val mode = if (enabled) state.mode else LedMode.OFF.code
            send(DiKeyProtocol.buildLedStrip(mode, bar, state.color.red, state.color.green, state.color.blue))
        }
        send(DiKeyProtocol.buildButtonBacklight(
            if (enabled) keys.red else 0, if (enabled) keys.green else 0, if (enabled) keys.blue else 0,
        ))
        val confirmed = withTimeoutOrNull(5_000) {
            var ok = true
            for (write in writes) ok = write.await() && ok
            ok
        } == true
        if (!confirmed) onStatus("Camp lighting failed: writes were rejected, disconnected or timed out")
        return confirmed
    }

    /** Which dial modes each dial is told it may enter (CMD 0x06/0x07). Defaults match the original setup. */
    data class AllowLists(
        val left: Set<Int> = DialDisplayType.leftCycleTypes,
        val right: Set<Int> = DialDisplayType.rightCycleTypes,
        val leftAllowNone: Boolean = false,
    )

    @Volatile
    var allowLists = AllowLists()
        private set

    /** Used by the DiKey probe's dial tools. Takes effect the next time the setup is sent. */
    fun setAllowLists(lists: AllowLists) {
        allowLists = lists
    }

    fun postStatus(msg: String) = onStatus(msg)

    fun attachSink(next: DiKeyFrameSink?) {
        sink = next
    }

    fun onTransportReady() {
        ready = false
        failPendingWrites()
        ready = true
        _dialDisplays.value = DialDisplays()
        rangeConfigured = false
        modesConfigured = false
        writeInFlight = false
        inFlight = null
        writeQueue.clear()
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
        onStatus("Ready — configuring dial modes…")
        configureDialInfrastructure()
    }

    fun onTransportDown(reason: String) {
        ready = false
        failPendingWrites()
        _dialDisplays.value = DialDisplays()
        writeQueue.clear()
        writeInFlight = false
        inFlight = null
        rangeConfigured = false
        modesConfigured = false
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
        onStatus(reason)
        onLinkDown()
    }

    fun ingestNotify(value: ByteArray) {
        if (value.isEmpty()) return
        val event = DiKeyProtocol.parseNotify(value)
            ?: DiKeyEvent.Raw("unparsed", value.toHex())
        if (event is DiKeyEvent.Encoder) {
            when (event.side) {
                "LEFT" -> {
                    lastDialTypeLeft = event.displayType
                    lastDialValueLeft = event.value
                }
                "RIGHT" -> {
                    lastDialTypeRight = event.displayType
                    lastDialValueRight = event.value
                }
            }
            if (ready && event.side in setOf("LEFT", "RIGHT")) {
                val left = event.side == "LEFT"
                val current = if (left) _dialDisplays.value.left else _dialDisplays.value.right
                val blanking = inFlight?.dialDisplay?.let {
                    it.left == left && it.displayType == DiKeyProtocol.DISPLAY_TYPE_BLANK
                } == true || writeQueue.any {
                    it.dialDisplay?.let { display ->
                        display.left == left && display.displayType == DiKeyProtocol.DISPLAY_TYPE_BLANK
                    } == true
                }
                if ((current?.displayType != DiKeyProtocol.DISPLAY_TYPE_BLANK && !blanking) ||
                    event.displayType == DiKeyProtocol.DISPLAY_TYPE_BLANK
                ) {
                    if (left) leftNotifyRevision++ else rightNotifyRevision++
                    updateDialDisplay(left, event.displayType, event.value)
                }
            }
        }
        onEvent(event)
    }

    fun onWriteComplete(success: Boolean) {
        if (!writeInFlight) return
        val done = inFlight
        writeInFlight = false
        inFlight = null
        if (success) {
            done?.onSuccess?.invoke()
            done?.dialDisplay?.let { display ->
                if (display.notifyRevisionAtDispatch == notifyRevision(display.left)) {
                    updateDialDisplay(display.left, display.displayType, display.value)
                }
            }
        } else {
            onStatus("Write failed · ${done?.logLabel ?: ""}")
        }
        drainWriteQueue()
        done?.onComplete?.invoke(success)
    }

    fun write(frame: ByteArray): Boolean = enqueue(OutboundWrite(frame))

    fun sendLed(mode: Int, position: Int, red: Int, green: Int, blue: Int): Boolean {
        if (!ready) {
            onStatus("Not ready to write")
            return false
        }
        return enqueue(
            OutboundWrite(
                bytes = DiKeyProtocol.buildLedStrip(if (lightingSuppressed) LedMode.OFF.code else mode,
                    position, red, green, blue),
                logLabel = "LED mode=$mode pos=$position rgb=$red,$green,$blue"
            )
        )
    }

    fun sendButtonBacklight(red: Int, green: Int, blue: Int): Boolean {
        return enqueue(
            OutboundWrite(
                bytes = DiKeyProtocol.buildButtonBacklight(
                    if (lightingSuppressed) 0 else red,
                    if (lightingSuppressed) 0 else green,
                    if (lightingSuppressed) 0 else blue),
                logLabel = "key backlight rgb=$red,$green,$blue"
            )
        )
    }

    fun sendDialDisplay(left: Boolean, displayType: Int, value: Int): Boolean {
        if (!ready) {
            onStatus("Not ready to write")
            return false
        }
        enqueueDialInfrastructure(restoreDialsAfter = false)

        val clamped = DialDisplayType.fromCode(displayType)?.clamp(value) ?: value
        val lastType = if (left) lastDialTypeLeft else lastDialTypeRight
        val switchDisplay = lastType == null || lastType != displayType
        val sideLabel = if (left) "LEFT" else "RIGHT"
        // Recorded at enqueue so a back-to-back write on the other dial reaffirms this
        // type, not the one still on the LCD.
        if (left) {
            lastDialTypeLeft = displayType
            lastDialValueLeft = clamped
        } else {
            lastDialTypeRight = displayType
            lastDialValueRight = clamped
        }
        writeQueue.addLast(
            OutboundWrite(
                bytes = DiKeyProtocol.buildDialDisplay(left, displayType, clamped, switchDisplay),
                dialDisplay = DialWrite(left, displayType, clamped),
                onSuccess = {
                    if (displayType != DiKeyProtocol.DISPLAY_TYPE_BLANK) {
                        val p = pendingRestore
                        pendingRestore = if (left) {
                            PendingDialRestore(
                                leftType = displayType,
                                leftValue = clamped,
                                rightType = p?.rightType ?: displayType,
                                rightValue = p?.rightValue ?: clamped
                            )
                        } else {
                            PendingDialRestore(
                                leftType = p?.leftType ?: displayType,
                                leftValue = p?.leftValue ?: clamped,
                                rightType = displayType,
                                rightValue = clamped
                            )
                        }
                    }
                },
                logLabel = "dial $sideLabel type=0x%02X val=%d switch=%d".format(
                    displayType, clamped, if (switchDisplay) 1 else 0
                )
            )
        )

        if (switchDisplay) {
            val otherLeft = !left
            val otherType = if (otherLeft) lastDialTypeLeft else lastDialTypeRight
            val otherValue = if (otherLeft) lastDialValueLeft else lastDialValueRight
            if (otherType != null && otherValue != null && otherType != displayType) {
                val otherLabel = if (otherLeft) "LEFT" else "RIGHT"
                writeQueue.addLast(
                    OutboundWrite(
                        bytes = DiKeyProtocol.buildDialDisplay(
                            otherLeft, otherType, otherValue, switchDisplay = true
                        ),
                        dialDisplay = DialWrite(otherLeft, otherType, otherValue),
                        logLabel = "dial $otherLabel reaffirm type=0x%02X val=%d".format(
                            otherType, otherValue
                        )
                    )
                )
            }
        }
        drainWriteQueue()
        return true
    }

    fun seedDialMemory(
        leftType: Int?,
        leftValue: Int?,
        rightType: Int?,
        rightValue: Int?
    ) {
        if (leftType != null && leftValue != null && rightType != null && rightValue != null) {
            pendingRestore = PendingDialRestore(leftType, leftValue, rightType, rightValue)
            onStatus(
                "Will restore L ${DiKeyProtocol.displayTypeLabel(leftType)}=$leftValue · " +
                    "R ${DiKeyProtocol.displayTypeLabel(rightType)}=$rightValue"
            )
        } else {
            pendingRestore = null
        }
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
    }

    fun seedLedMemory(snapshot: com.sphy.airconcontroller.storage.LedRestoreSnapshot) {
        pendingLed = snapshot
        val summary = if (snapshot.bars.isEmpty()) {
            "(no strip bars)"
        } else {
            snapshot.bars.entries.sortedBy { it.key }.joinToString(" · ") { (bar, state) ->
                "bar$bar rgb=${state.color.red},${state.color.green},${state.color.blue}"
            }
        }
        val keys = snapshot.backlight?.let { "rgb=${it.red},${it.green},${it.blue}" } ?: "(unset)"
        onStatus("Will restore LED $summary · keys $keys")
    }

    /** Skip LED restore on connect until [LightingScheduler] has a cabin reading. */
    fun clearPendingLed() {
        pendingLed = null
    }

    fun restoreSeededDialsToDevice(): Boolean {
        if (!ready) return false
        val pending = pendingRestore
        val led = pendingLed
        if (pending == null && led == null) {
            onStatus("Ready — listening for button / dial events (no prefs)")
            return false
        }
        if (pending != null) {
            lastDialTypeLeft = null
            lastDialValueLeft = null
            lastDialTypeRight = null
            lastDialValueRight = null
            onStatus(
                "Restoring dials · L ${DiKeyProtocol.displayTypeLabel(pending.leftType)}=${pending.leftValue} · " +
                    "R ${DiKeyProtocol.displayTypeLabel(pending.rightType)}=${pending.rightValue}"
            )
            sendDialDisplay(left = true, displayType = pending.leftType, value = pending.leftValue)
            sendDialDisplay(left = false, displayType = pending.rightType, value = pending.rightValue)
        }
        if (led != null) {
            for (bar in led.bars.keys.sorted()) {
                val state = led.bars[bar] ?: continue
                onStatus(
                    "Restoring LED bar$bar · mode=${state.mode} " +
                        "rgb=${state.color.red},${state.color.green},${state.color.blue}"
                )
                sendLed(state.mode, bar, state.color.red, state.color.green, state.color.blue)
            }
            val bl = led.backlight
            if (bl != null) {
                onStatus("Restoring key backlight · rgb=${bl.red},${bl.green},${bl.blue}")
                sendButtonBacklight(bl.red, bl.green, bl.blue)
            }
        }
        onStatus("Ready — listening for button / dial events")
        return true
    }

    fun configureDialInfrastructure(restoreDialsAfter: Boolean = true): Boolean {
        if (!ready) {
            onStatus("Not ready to write")
            return false
        }
        modesConfigured = false
        enqueueDialInfrastructure(restoreDialsAfter)
        drainWriteQueue()
        return true
    }

    private fun enqueueDialInfrastructure(restoreDialsAfter: Boolean) {
        if (!rangeConfigured) {
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderRangeConfig(),
                    onSuccess = { rangeConfigured = true },
                    logLabel = "range 0x08 fan=1-7 media=0-39 vol=0-10"
                )
            )
        }
        if (!modesConfigured) {
            val lists = allowLists
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderModeConfig(
                        left = false,
                        lists.right
                    ),
                    logLabel = "mode RIGHT 0x06 types=${lists.right}"
                )
            )
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderModeConfig(
                        left = true,
                        lists.left,
                        allowNone = lists.leftAllowNone
                    ),
                    onSuccess = {
                        modesConfigured = true
                        if (restoreDialsAfter) {
                            restoreSeededDialsToDevice()
                        } else {
                            onStatus("Ready — listening for button / dial events")
                        }
                        onDialsReady()
                    },
                    logLabel = "mode LEFT 0x07 types=${lists.left}" + if (lists.leftAllowNone) " +None" else ""
                )
            )
        } else if (restoreDialsAfter) {
            restoreSeededDialsToDevice()
        }
    }

    private fun enqueue(item: OutboundWrite): Boolean {
        if (!ready || sink == null) {
            onStatus("Not ready to write")
            return false
        }
        writeQueue.addLast(item)
        drainWriteQueue()
        return true
    }

    private fun drainWriteQueue() {
        if (writeInFlight) return
        val link = sink ?: return
        val item = writeQueue.removeFirstOrNull() ?: return
        writeInFlight = true
        inFlight = item
        val frame = item.bytes
        item.dialDisplay?.let { it.notifyRevisionAtDispatch = notifyRevision(it.left) }
        if (!link.writeFrame(frame)) {
            writeInFlight = false
            inFlight = null
            onStatus("Write enqueue failed · ${item.logLabel ?: frame.toHex()}")
            drainWriteQueue()
            item.onComplete?.invoke(false)
        } else {
            onStatus("TX · ${item.logLabel ?: frame.toHex()} · ${frame.toHex()}")
        }
    }

    private fun ByteArray.toHex(): String = with(DiKeyProtocol) { toHex() }

    private fun failPendingWrites() {
        val pending = listOfNotNull(inFlight) + writeQueue.toList()
        writeQueue.clear()
        inFlight = null
        writeInFlight = false
        pending.forEach { it.onComplete?.invoke(false) }
    }

    private fun notifyRevision(left: Boolean): Long = if (left) leftNotifyRevision else rightNotifyRevision

    private fun updateDialDisplay(left: Boolean, displayType: Int, value: Int) {
        val display = DialDisplay(displayType, value)
        _dialDisplays.value = if (left) {
            _dialDisplays.value.copy(left = display)
        } else {
            _dialDisplays.value.copy(right = display)
        }
    }

    private data class PendingDialRestore(
        val leftType: Int,
        val leftValue: Int,
        val rightType: Int,
        val rightValue: Int
    )

    private data class OutboundWrite(
        val bytes: ByteArray,
        val onSuccess: (() -> Unit)? = null,
        val onComplete: ((Boolean) -> Unit)? = null,
        val dialDisplay: DialWrite? = null,
        val logLabel: String? = null
    )

    private data class DialWrite(
        val left: Boolean,
        val displayType: Int,
        val value: Int,
        var notifyRevisionAtDispatch: Long = 0,
    )
}
