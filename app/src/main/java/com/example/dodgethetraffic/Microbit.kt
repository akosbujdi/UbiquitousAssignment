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

class Microbit(ctx: Context, private val onCommand: (String) -> Unit) {

    private val c = ctx.applicationContext
    private val h = Handler(c.mainLooper)
    private val a = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var g: BluetoothGatt? = null
    private var req: BluetoothGattCharacteristic? = null
    private var evt: BluetoothGattCharacteristic? = null          // notify FROM micro:bit
    private var clientEvt: BluetoothGattCharacteristic? = null     // write TO micro:bit  ✅

    private var scanning = false
    private var connected = false
    companion object { private var busy = false }

    private val SVC       = UUID.fromString("E95D93AF-251D-470A-A062-FA1922DFA9A8")
    private val EVT       = UUID.fromString("E95D9775-251D-470A-A062-FA1922DFA9A8")   // MicroBit Event (notify)
    private val CLIENTEVT = UUID.fromString("E95D5404-251D-470A-A062-FA1922DFA9A8")   // Client Event (write) ✅
    private val REQ       = UUID.fromString("E95D23C4-251D-470A-A062-FA1922DFA9A8")
    private val CCCD      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val EVT_LANE = 1104
    private val EVT_SHIELD = 1106
    private val EVT_CRASH = 1200

    private var reqStep = 0

    fun isConnected() = connected

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (scanning || busy) return
        if (a == null || !a.isEnabled) return
        val s = a.bluetoothLeScanner ?: return
        busy = true; scanning = true
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        h.postDelayed({ stopScan() }, 10_000)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        stopScan()
        try { g?.disconnect(); g?.close() } catch (_: Exception) {}
        g = null; req = null; evt = null; clientEvt = null
        connected = false; busy = false
    }

    // ✅ CALL THIS when you crash (GameActivity)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCrashIcon() {
        val gatt = g ?: return
        val ch = clientEvt ?: return   // ✅ write to Client Event

        val payload = pkt(EVT_CRASH, 1)

        val writeType =
            if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, payload, writeType)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = payload
                gatt.writeCharacteristic(ch)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { a?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        h.removeCallbacksAndMessages(null)
    }

    private val scanCb = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            if (!name.contains("micro:bit", true)) return
            stopScan()
            connect(r.device)
        }
        override fun onScanFailed(errorCode: Int) { stopScan(); busy = false }
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        try { g?.close() } catch (_: Exception) {}
        g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            d.connectGatt(c, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        else d.connectGatt(c, false, gattCb)
    }

    private val gattCb = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true; g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) disconnect()
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()
            val svc = g.getService(SVC) ?: return disconnect()

            req = svc.getCharacteristic(REQ) ?: return disconnect()
            evt = svc.getCharacteristic(EVT) ?: return disconnect()
            clientEvt = svc.getCharacteristic(CLIENTEVT) ?: return disconnect()   // ✅

            g.setCharacteristicNotification(evt, true)
            val d = evt!!.getDescriptor(CCCD) ?: return disconnect()

            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()
            reqStep = 0
            writeReq(g, EVT_LANE)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return disconnect()
            if (reqStep == 0) { reqStep = 1; writeReq(g, EVT_SHIELD) }
        }

        private fun writeReq(g: BluetoothGatt, type: Int) {
            val r = req ?: return
            val p = pkt(type, 0)
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(r, p, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            else { @Suppress("DEPRECATION") r.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; r.value = p; g.writeCharacteristic(r) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = handle(value)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handle(ch.value ?: return)
        }

        private fun handle(b: ByteArray) {
            var i = 0
            while (i + 3 < b.size) {
                val t = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                val v = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
                i += 4
                when (t) {
                    EVT_LANE -> h.post { onCommand("LANE:$v") }
                    EVT_SHIELD -> h.post { onCommand("SHIELD") }
                }
            }
        }
    }

    private fun pkt(t: Int, v: Int) = byteArrayOf(
        (t and 0xFF).toByte(), ((t shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}
