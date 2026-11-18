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
                            return devices.map { device ->
                                checkNotNull(device.javaClass.getMethod("getDeviceId").invoke(device) as? String) {
                                    "Invalid connected CarPlay device ID."
                                }
                            }
                        } catch (e: ReflectiveOperationException) {
                            throw IllegalStateException("Cannot identify connected CarPlay device: ${e.message}", e)
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
