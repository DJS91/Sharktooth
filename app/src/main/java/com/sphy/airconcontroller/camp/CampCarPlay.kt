package com.sphy.airconcontroller.camp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class CampCarPlay(private val connect: () -> Client?) {
    constructor(context: Context) : this({ bind(context.applicationContext) })

    internal interface Client : Closeable {
        fun state(): Int
        fun activeDeviceIds(): List<String>
        fun disconnect(deviceId: String)
    }

    suspend fun disconnectIfConnected(): CampResult = withContext(Dispatchers.IO) {
        val client = connect() ?: return@withContext CampResult(true, "OEM CarPlay not installed; skipped.")
        client.use {
            var requested = false
            repeat(20) {
                when (val state = client.state()) {
                    0, 4, 5 -> return@withContext CampResult(true,
                        if (requested) "CarPlay disconnected; OEM session state=$state confirmed."
                        else "No active CarPlay session; OEM state=$state. No disconnect needed.")
                    1, 2 -> if (!requested) {
                        val ids = client.activeDeviceIds()
                        check(ids.isNotEmpty() && ids.all { it.isNotBlank() }) {
                            "CarPlay is active but its connected device ID is unavailable; no disconnect sent."
                        }
                        ids.distinct().forEach(client::disconnect)
                        requested = true
                    }
                    3 -> Unit
                    else -> error("Unrecognized OEM CarPlay session state: $state")
                }
                delay(250)
            }
            CampResult(false, "CarPlay disconnect not confirmed; OEM state=${client.state()}.")
        }
    }

    companion object {
        private const val PACKAGE = "com.ts.carplay"
        private const val API = "com.ts.carplay.common.aidl.ICarPlayService"

        internal fun resolveDeviceIds(devices: List<Any>, connections: () -> Collection<*>): List<String> {
            fun text(device: Any, getter: String) =
                (device.javaClass.getMethod(getter).invoke(device) as? String)?.takeIf { it.isNotBlank() }
            val connectedDevices by lazy(connections)
            return devices.map { device ->
                text(device, "getDeviceId") ?: run {
                    val bluetooth = text(device, "getBtAddr")
                    val usb = text(device, "getUsbSerialNumber")
                    check(bluetooth != null || usb != null) {
                        "Connected CarPlay device has no ID or transport identity; no disconnect sent."
                    }
                    val matches = connectedDevices.filterNotNull().filter { candidate ->
                        if (bluetooth != null) bluetooth.equals(text(candidate, "getBtAddr"), ignoreCase = true)
                        else usb == text(candidate, "getUsbSerialNumber")
                    }
                    check(matches.size == 1) {
                        "Cannot uniquely match active CarPlay to the OEM connection manager; no disconnect sent."
                    }
                    checkNotNull(text(matches.single(), "getDeviceId")) {
                        "Connected CarPlay device ID unavailable in the OEM connection manager; no disconnect sent."
                    }
                }
            }.distinct()
        }

        private fun connectionDevices(app: Context, loader: ClassLoader): Collection<*> {
            val api = Class.forName("ts.car.service.dcm.IDeviceConnectionManager", true, loader)
            val manager = checkNotNull(Class.forName("com.ts.car.core.ServiceProvider", true, loader)
                .getMethod("get", Class::class.java, Context::class.java).invoke(null, api, app)) {
                "OEM connection manager unavailable."
            }
            repeat(20) {
                if (api.getMethod("isServiceConnected").invoke(manager) == true) {
                    return checkNotNull(api.getMethod("getDeviceList").invoke(manager) as? Map<*, *>) {
                        "OEM connection-manager device list unavailable."
                    }.values.toList()
                }
                Thread.sleep(250)
            }
            error("OEM connection manager connection timed out.")
        }

        private fun bind(app: Context): Client? {
            val info = try {
                app.packageManager.getApplicationInfo(PACKAGE, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
            val binder = AtomicReference<IBinder?>()
            val ready = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    binder.set(service)
                    ready.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) { binder.set(null) }
                override fun onNullBinding(name: ComponentName) { ready.countDown() }
            }
            val intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.CarPlayService"))
            check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Cannot bind OEM CarPlay service." }
            var handedOff = false
            try {
                check(ready.await(5, TimeUnit.SECONDS)) { "OEM CarPlay service connection timed out." }
                val serviceBinder = checkNotNull(binder.get()) { "OEM CarPlay service unavailable." }
                val loader = PathClassLoader(info.sourceDir, app.classLoader)
                val api = Class.forName(API, true, loader)
                val service = checkNotNull(Class.forName("$API\$Stub", true, loader)
                    .getMethod("asInterface", IBinder::class.java).invoke(null, serviceBinder)) {
                    "OEM CarPlay client unavailable."
                }
                val connected = Class.forName("com.ts.carplay.common.CarPlayConstants\$DeviceStatus", true, loader)
                    .getField("CONNECTED").getInt(null)
                fun invoke(name: String, vararg args: Any): Any? {
                    check(binder.get()?.isBinderAlive == true) { "OEM CarPlay service disconnected." }
                    return try {
                        api.getMethod(name, *args.map { it.javaClass }.toTypedArray()).invoke(service, *args)
                    } catch (e: ReflectiveOperationException) {
                        val cause = (e as? InvocationTargetException)?.targetException ?: e
                        throw IllegalStateException("CarPlay $name failed: ${cause.message}", cause)
                    }
                }
                return object : Client {
                    override fun state(): Int =
                        checkNotNull(invoke("getCarPlaySessionStatus") as? Int) { "Invalid CarPlay state reading." }

                    override fun activeDeviceIds(): List<String> {
                        try {
                            val wireless = invoke("getActivedWirelessDevice")
                            val devices = if (wireless != null) listOf(wireless) else {
                                checkNotNull(invoke("getCarPlayDeviceList") as? List<*>) { "CarPlay device list unavailable." }
                                    .filterNotNull().filter { device ->
                                        device.javaClass.getMethod("getDeviceStatus").invoke(device) == connected
                                    }
                            }
                            return resolveDeviceIds(devices) { connectionDevices(app, loader) }
                        } catch (e: ReflectiveOperationException) {
                            throw IllegalStateException("Cannot identify connected CarPlay device: ${e.message}", e)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException("CarPlay device lookup interrupted.", e)
                        }
                    }

                    override fun disconnect(deviceId: String) { invoke("disconnectCarPlay", deviceId) }
                    override fun close() { app.unbindService(connection) }
                }.also { handedOff = true }
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Cannot load OEM CarPlay client: ${e.message}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("OEM CarPlay connection interrupted.", e)
            } finally {
                if (!handedOff) app.unbindService(connection)
            }
        }
    }
}
