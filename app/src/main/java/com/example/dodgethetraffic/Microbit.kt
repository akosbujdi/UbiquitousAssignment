package com.example.dodgethetraffic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class Microbit(
    context: Context,
    private val onCommand: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(appContext.mainLooper)

    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter

    private var gatt: BluetoothGatt? = null
    private var eventChar: BluetoothGattCharacteristic? = null
    private var reqChar: BluetoothGattCharacteristic? = null

    private val scanning = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    companion object {
        // prevents MainActivity + GameActivity both trying to connect at once
        private val busy = AtomicBoolean(false)
    }

    // micro:bit Event Service UUIDs
    private val EVENT_SERVICE_UUID = UUID.fromString("E95D93AF-251D-470A-A062-FA1922DFA9A8")
    private val EVENT_CHAR_UUID    = UUID.fromString("E95D9775-251D-470A-A062-FA1922DFA9A8")
    private val REQ_CHAR_UUID      = UUID.fromString("E95D23C4-251D-470A-A062-FA1922DFA9A8")
    private val CCCD_UUID          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val EVT_LANE = 1104

    private val scanTimeout = Runnable {
        stopScan()
        cleanup("scan timeout")
    }

    fun isConnected(): Boolean = connected.get()

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            Log.e("MicrobitBT", "Bluetooth off / not available")
            return
        }

        if (!busy.compareAndSet(false, true)) {
            Log.d("MicrobitBT", "busy -> ignoring startScan")
            return
        }

        if (!scanning.compareAndSet(false, true)) {
            busy.set(false)
            return
        }

        connecting.set(false)
        connected.set(false)

        val scanner = a.bluetoothLeScanner ?: run {
            cleanup("no scanner")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d("MicrobitBT", "Starting scan…")
        scanner.startScan(null, settings, scanCallback)

        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, 10_000)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        cleanup("disconnect()")
    }

    // ------------------------------------------------------------
    // Scan
    // ------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            stopScan()
            cleanup("scan failed $errorCode")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (connecting.get() || connected.get()) return

            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName

            Log.d("MicrobitBT", "Scan: name=$name addr=${device.address} rssi=${result.rssi}")

            if (name.isNullOrBlank() || !name.contains("micro:bit", ignoreCase = true)) return
            if (!connecting.compareAndSet(false, true)) return

            stopScan()
            connect(device)
        }
    }

    private fun stopScan() {
        if (!scanning.getAndSet(false)) return
        handler.removeCallbacks(scanTimeout)
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        Log.d("MicrobitBT", "Scan stopped")
    }

    // ------------------------------------------------------------
    // Connect + GATT
    // ------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.d("MicrobitBT", "Connecting to ${device.address}…")

        try { gatt?.close() } catch (_: Exception) {}
        gatt = null

        handler.postDelayed({
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
        }, 200)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d("MicrobitBT", "Conn state: status=$status newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.set(true)
                handler.postDelayed({
                    Log.d("MicrobitBT", "Connected → discoverServices()")
                    g.discoverServices()
                }, 150)
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                if (gatt == g) gatt = null
                cleanup("gatt disconnected status=$status")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d("MicrobitBT", "Services discovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                return
            }

            val svc = g.getService(EVENT_SERVICE_UUID) ?: run {
                Log.e("MicrobitBT", "Event service missing")
                disconnect()
                return
            }

            eventChar = svc.getCharacteristic(EVENT_CHAR_UUID)
            reqChar = svc.getCharacteristic(REQ_CHAR_UUID)

            if (eventChar == null || reqChar == null) {
                Log.e("MicrobitBT", "Event/Req char missing")
                disconnect()
                return
            }

            // Enable notifications for eventChar
            g.setCharacteristicNotification(eventChar, true)

            val cccd = eventChar!!.getDescriptor(CCCD_UUID) ?: run {
                Log.e("MicrobitBT", "CCCD missing")
                disconnect()
                return
            }

            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }

            Log.d("MicrobitBT", "CCCD write started ok=$ok")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("MicrobitBT", "CCCD write status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                return
            }

            // Register EVT_LANE only
            val req = reqChar ?: return
            val payload = reqPacket(EVT_LANE, 0)

            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(req, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                run {
                    req.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    req.value = payload
                    g.writeCharacteristic(req)
                }
            }

            Log.d("MicrobitBT", "Register EVT_LANE write ok=$ok")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != REQ_CHAR_UUID) return

            Log.d("MicrobitBT", "REQ write status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("MicrobitBT", "Registered EVT_LANE ✅ ready")
                connecting.set(false)
            } else {
                disconnect()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleEvent(value)
        }

        // Older Android
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            handleEvent(v)
        }

        private fun handleEvent(bytes: ByteArray) {
            var i = 0
            while (i + 3 < bytes.size) {
                val type = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                val value = (bytes[i + 2].toInt() and 0xFF) or ((bytes[i + 3].toInt() and 0xFF) shl 8)
                i += 4

                if (type == EVT_LANE) {
                    val msg = "LANE:$value"
                    Log.d("MicrobitBT", msg)
                    handler.post { onCommand(msg) }
                }
            }
        }
    }

    private fun reqPacket(eventType: Int, eventValue: Int): ByteArray {
        return byteArrayOf(
            (eventType and 0xFF).toByte(),
            ((eventType shr 8) and 0xFF).toByte(),
            (eventValue and 0xFF).toByte(),
            ((eventValue shr 8) and 0xFF).toByte()
        )
    }

    private fun cleanup(reason: String) {
        Log.d("MicrobitBT", "Cleanup: $reason")
        stopScan()
        scanning.set(false)
        connecting.set(false)
        connected.set(false)
        eventChar = null
        reqChar = null
        busy.set(false)
    }
}
