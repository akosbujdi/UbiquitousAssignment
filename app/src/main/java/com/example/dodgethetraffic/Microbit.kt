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
import androidx.annotation.RequiresPermission
import java.util.UUID


 // Microbit:
 //  Scans for a "BBC micro:bit ..." BLE device
 // Connects + discovers GATT services
 // Subscribes to micro:bit Event notifications (so micro:bit can send us "LANE" and "SHIELD")
 // Registers which event types we want to receive (EVT_LANE + EVT_SHIELD) using the "REQ" characteristic
 // Sends a crash event back to micro:bit (EVT_CRASH) using "Client Event" characteristic

class Microbit(ctx: Context, private val onCommand: (String) -> Unit) {

    // App context + main thread handler (we post results back onto UI thread safely)
    private val c = ctx.applicationContext
    private val h = Handler(c.mainLooper)

    // Bluetooth adapter for scanning and making entries
    private val a = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // Current GATT connection (null when not connected)
    private var g: BluetoothGatt? = null

    // GATT characteristics we need:
    private var req: BluetoothGattCharacteristic? = null        // Event requests
    private var evt: BluetoothGattCharacteristic? = null        // Event notifications from microbit
    private var clientEvt: BluetoothGattCharacteristic? = null  // Client Even writing to Microbit

    // Simple state flags
    private var scanning = false
    private var connected = false

    // Shared lock so MainActivity + GameActivity don't both connect at the same time
    companion object { private var busy = false }

    //UUID's for the microbit sevice and characteristics gotten from a BLE Scanner
    private val SVC       = UUID.fromString("E95D93AF-251D-470A-A062-FA1922DFA9A8")
    private val EVT       = UUID.fromString("E95D9775-251D-470A-A062-FA1922DFA9A8")   // notify from micro:bit
    private val CLIENTEVT = UUID.fromString("E95D5404-251D-470A-A062-FA1922DFA9A8")   // write to micro:bit ✅
    private val REQ       = UUID.fromString("E95D23C4-251D-470A-A062-FA1922DFA9A8")   // write to register events
    private val CCCD      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")   // descriptor to enable notifications

    // ---- Your custom event type IDs ----
    // These must match the event IDs used in your MakeCode (bluetooth.raiseEvent(type, value))
    private val EVT_LANE   = 1104   // sends lane index in value (0..2)
    private val EVT_SHIELD = 1106   // sends shield trigger (value not important)
    private val EVT_CRASH  = 1200   // we send this TO micro:bit when the player crashes

    // Used to register EVT_LANE first, then EVT_SHIELD right after
    private var reqStep = 0

    fun isConnected() = connected


    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        // already scanning or someone else is connecting -> ignore
        if (scanning || busy) return

        // adapter missing or bluetooth off -> can't scan
        if (a == null || !a.isEnabled) return

        val s = a.bluetoothLeScanner ?: return

        busy = true
        scanning = true

        // Start scanning with low latency for faster discovery
        s.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb
        )

        // Safety: stop scan after 10 seconds
        h.postDelayed({ stopScan() }, 10_000)
    }

    //Disconnect from Microbit and Cleanup
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        stopScan()

        // Close GATT safely
        try { g?.disconnect(); g?.close() } catch (_: Exception) {}

        // Reset all references/state
        g = null
        req = null
        evt = null
        clientEvt = null

        connected = false
        busy = false
    }

   //Sends a Crash event to the micro:bit with the Event 1200
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCrashIcon() {
        val gatt = g ?: return // not connected
        val ch = clientEvt ?: return // writing to micro:bit

        // Build the payload

        // Packet is (type, value) as 2 little-endian shorts (4 bytes total)
        val payload = pkt(EVT_CRASH, 1)
       if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
       else {
           @Suppress("DEPRECATION")
           ch.value = payload
           @Suppress("DEPRECATION")
           gatt.writeCharacteristic(ch)
       }

   }

    /**
     * Stop scanning (safe to call multiple times).
     */
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false

        try { a?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}

        // remove any pending callbacks (like scan timeout)
        h.removeCallbacksAndMessages(null)
    }

    /**
     * Scan callback:
     * - Filters for device name containing "micro:bit"
     * - Stops scan and connects when found
     */
    private val scanCb = object : ScanCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return

            // Only accept micro:bit devices
            if (!name.contains("micro:bit", true)) return

            stopScan()
            connect(r.device)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            busy = false
        }
    }

    /**
     * Connect to the BLE device and create a GATT session.
     */
    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        // If we had a previous gatt, close it first
        try { g?.close() } catch (_: Exception) {}

        g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            d.connectGatt(c, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else
            d.connectGatt(c, false, gattCb)
    }


    private val gattCb = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Connected successfully -> discover services
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                g.discoverServices()
            }
            // Disconnected -> cleanup
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        /**
         * After service discovery, grab the characteristics we need.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()

            val svc = g.getService(SVC) ?: return disconnect()

            // Required characteristics
            req = svc.getCharacteristic(REQ) ?: return disconnect()
            evt = svc.getCharacteristic(EVT) ?: return disconnect()
            clientEvt = svc.getCharacteristic(CLIENTEVT) ?: return disconnect() // ✅ needed for crash icon

            // Turn on notifications so micro:bit can push events to us via EVT characteristic
            g.setCharacteristicNotification(evt, true)

            // CCCD descriptor must be written to actually enable notifications on the device side
            val d = evt!!.getDescriptor(CCCD) ?: return disconnect()

            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }


        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()

            // Step 0 = register lane first
            reqStep = 0
            writeReq(g, EVT_LANE)
        }

        //Called when a characteristic write finishes.
        // Used it to chain the 2 registrations: EVT_LANE -> EVT_SHIELD
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()

            // After lane register succeeds, register shield next
            if (reqStep == 0) {
                reqStep = 1
                writeReq(g, EVT_SHIELD)
            }
        }


        // Writes the registration packet to REQ
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun writeReq(g: BluetoothGatt, type: Int) {
            val r = req ?: return
            val p = pkt(type, 0)

            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(r, p, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                r.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                r.value = p
                @Suppress("DEPRECATION")
                g.writeCharacteristic(r)
            }
        }

        /**
         * Android 13+ notification callback: gives you the bytes directly.
         */
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            handle(value)

        /**
         * Older Android callback: you read ch.value.
         */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handle(ch.value ?: return)
        }


        private fun handle(b: ByteArray) {
            var i = 0
            while (i + 3 < b.size) {
                // 16-bit little-endian event type
                val t = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

                // 16-bit little-endian event value
                val v = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)

                i += 4

                // Convert micro:bit events into simple string commands for GameActivity
                when (t) {
                    EVT_LANE -> h.post { onCommand("LANE:$v") }   // e.g. "LANE:2"
                    EVT_SHIELD -> h.post { onCommand("SHIELD") } // shield trigger
                }
            }
        }
    }

    // Builds the 4-byte micro:bit event packet I need when writing back to the micro:bit.
    // Same format as above: 2 bytes type + 2 bytes value, all little-endian.
    private fun pkt(t: Int, v: Int) = byteArrayOf(
        (t and 0xFF).toByte(), ((t shr 8) and 0xFF).toByte(),   // type low/high
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()    // value low/high
    )
}
