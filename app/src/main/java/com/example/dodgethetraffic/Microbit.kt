package com.example.dodgethetraffic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class Microbit(
    context: Context,
    private val onCommand: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(appContext.mainLooper)

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null

    private val scanning = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    companion object {
        // Prevents 2 Activities from connecting at the same time
        private val busy = AtomicBoolean(false)
    }

    // micro:bit Event Service UUIDs (matches your logs)
    private val EVENT_SERVICE_UUID = UUID.fromString("E95D93AF-251D-470A-A062-FA1922DFA9A8")
    private val EVENT_CHAR_UUID    = UUID.fromString("E95D9775-251D-470A-A062-FA1922DFA9A8")
    private val REQ_CHAR_UUID      = UUID.fromString("E95D23C4-251D-470A-A062-FA1922DFA9A8")
    private val CCCD_UUID          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var eventChar: BluetoothGattCharacteristic? = null
    private var reqChar: BluetoothGattCharacteristic? = null

    private val EVT_LANE = 1104

    private val scanTimeout = Runnable {
        stopScanInternal()
        cleanup("scan timeout")
    }

    fun isConnected(): Boolean = connected.get()

    // ---------------- Permissions (self-checked) ----------------

    private fun hasPerm(perm: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            appContext.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun canScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPerm(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Many devices need location permission for BLE scan pre-Android 12
            hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun canConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
        } else true
    }

    // ---------------- Public API ----------------

    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            Log.e("MicrobitBT", "Bluetooth off / not available")
            return
        }

        // If busy ever got stuck but there is no active state, free it
        if (busy.get() && gatt == null && !scanning.get() && !connecting.get() && !connected.get()) {
            busy.set(false)
        }

        if (!canScan()) {
            Log.e("MicrobitBT", "Missing permission: BLUETOOTH_SCAN (or Location on <12)")
            cleanup("missing scan permission")
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

        // reset connection state
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

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e("MicrobitBT", "SecurityException starting scan: ${e.message}")
            cleanup("scan SecurityException")
            return
        }

        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, 10_000)
    }

    fun disconnect() {
        stopScanInternal()

        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
            // ignore
        } catch (_: Exception) {
            // ignore
        }

        handler.postDelayed({
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
        }, 150)

        cleanup("disconnect()")
    }

    // ---------------- Scan ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            stopScanInternal()
            cleanup("scan failed $errorCode")
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (connecting.get() || connected.get()) return

            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName

            Log.d("MicrobitBT", "Scan: name=$name addr=${device.address} rssi=${result.rssi}")

            if (name.isNullOrBlank() || !name.contains("micro:bit", ignoreCase = true)) return

            if (!connecting.compareAndSet(false, true)) return

            stopScanInternal()
            connect(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        if (!scanning.getAndSet(false)) return
        handler.removeCallbacks(scanTimeout)
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        Log.d("MicrobitBT", "Scan stopped")
    }

    // ---------------- Connect + GATT ----------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!canConnect()) {
            Log.e("MicrobitBT", "Missing permission: BLUETOOTH_CONNECT")
            cleanup("missing connect permission")
            return
        }

        Log.d("MicrobitBT", "Connecting to ${device.address}…")

        try { gatt?.close() } catch (_: Exception) {}
        gatt = null

        // Optional bonding attempt (wrapped)
        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
        } catch (_: Exception) {}

        handler.postDelayed({
            try {
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(appContext, false, gattCallback)
                }
            } catch (e: SecurityException) {
                Log.e("MicrobitBT", "SecurityException connectGatt: ${e.message}")
                cleanup("connectGatt SecurityException")
            }
        }, 200)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d("MicrobitBT", "Conn state: status=$status newState=$newState")

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.set(true)

                // Stability tweaks (wrapped)
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) g.requestMtu(185) } catch (_: Exception) {}

                handler.postDelayed({
                    Log.d("MicrobitBT", "Connected → discoverServices()")
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e("MicrobitBT", "SecurityException discoverServices: ${e.message}")
                        disconnect()
                    }
                }, 150)
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                if (gatt == g) gatt = null
                cleanup("gatt disconnected status=$status")
            }
        }

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

            // Enable notifications
            try {
                g.setCharacteristicNotification(eventChar, true)
            } catch (e: SecurityException) {
                Log.e("MicrobitBT", "SecurityException setCharacteristicNotification: ${e.message}")
                disconnect()
                return
            }

            val cccd = eventChar!!.getDescriptor(CCCD_UUID) ?: run {
                Log.e("MicrobitBT", "CCCD missing")
                disconnect()
                return
            }

            val ok = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            } catch (e: SecurityException) {
                Log.e("MicrobitBT", "SecurityException writeDescriptor: ${e.message}")
                disconnect()
                return
            }

            Log.d("MicrobitBT", "CCCD write started ok=$ok")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d("MicrobitBT", "CCCD write status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                return
            }

            // Register EVT_LANE only
            val req = reqChar ?: return
            val payload = reqPacket(EVT_LANE, 0)

            handler.postDelayed({
                val ok = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(req, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            req.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            req.value = payload
                            g.writeCharacteristic(req)
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e("MicrobitBT", "SecurityException writeCharacteristic: ${e.message}")
                    disconnect()
                    return@postDelayed
                }

                Log.d("MicrobitBT", "Register EVT_LANE write ok=$ok")
            }, 120)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != REQ_CHAR_UUID) return

            Log.d("MicrobitBT", "REQ write status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("MicrobitBT", "Registered EVT_LANE ✅ ready")
                connected.set(true)
                connecting.set(false)
            } else {
                disconnect()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
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
        stopScanInternal()
        scanning.set(false)
        connecting.set(false)
        connected.set(false)
        eventChar = null
        reqChar = null
        busy.set(false)
    }
}


