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
import java.util.ArrayDeque
import java.util.UUID

class Microbit(
    private val context: Context,
    private val onCommand: (String) -> Unit
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var pendingDevice: BluetoothDevice? = null
    private val handler = Handler(context.mainLooper)

    // ----------------------------------------------------------
    // UART SERVICE UUIDs (MakeCode UART)
    // ----------------------------------------------------------
    private val UART_SERVICE_UUID =
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    // micro:bit → Android (notifications)
    private val UART_TX_CHAR_UUID =
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    // Android → micro:bit (optional)
    private val UART_RX_CHAR_UUID =
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    // CCCD descriptor to enable notifications
    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val descriptorWriteQueue: ArrayDeque<BluetoothGattDescriptor> = ArrayDeque()

    // ----------------------------------------------------------
    // Start BLE Scan
    // ----------------------------------------------------------
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("MicrobitBT", "Bluetooth not available or not enabled")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d("MicrobitBT", "Starting BLE scan…")

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e("MicrobitBT", "Missing BLE scan permission: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // ----------------------------------------------------------
    // Scan Callback
    // ----------------------------------------------------------
    private val scanCallback = object : ScanCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val device = result.device
            val record = result.scanRecord
            val nameFromDevice = device.name
            val nameFromAdvert = record?.deviceName
            val displayName = nameFromDevice ?: nameFromAdvert

            Log.d(
                "MicrobitBT",
                "Scan result: name=$displayName addr=${device.address}"
            )

            // Detect a micro:bit by name
            if (!displayName.isNullOrEmpty() &&
                displayName.contains("micro:bit", ignoreCase = true)
            ) {
                Log.d("MicrobitBT", ">>> Found micro:bit ($displayName), connecting…")

                pendingDevice = device
                stopScan()
                connectToDevice(device)
            }
        }
    }

    // ----------------------------------------------------------
    // Connect
    // ----------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d("MicrobitBT", "Preparing connection to ${device.address}")

        handler.postDelayed({
            Log.d("MicrobitBT", "Connecting via GATT…")

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        }, 500)
    }

    // ----------------------------------------------------------
    // GATT Callback
    // ----------------------------------------------------------
    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("MicrobitBT", "onConnectionStateChange status=$status newState=$newState")

            if (status == 133) {
                Log.e("MicrobitBT", "GATT error 133, retrying…")
                disconnect()
                handler.postDelayed({
                    pendingDevice?.let { connectToDevice(it) }
                }, 1000)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("MicrobitBT", "Connected! Discovering services…")
                gatt.discoverServices()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d("MicrobitBT", "Services discovered")

            for (s in gatt.services)
                Log.d("MicrobitBT", "Found service: ${s.uuid}")

            descriptorWriteQueue.clear()

            val uartService = gatt.getService(UART_SERVICE_UUID)
            if (uartService == null) {
                Log.e("MicrobitBT", "UART service NOT found!")
                return
            }

            val tx = uartService.getCharacteristic(UART_TX_CHAR_UUID)

            if (tx == null) {
                Log.e("MicrobitBT", "UART TX characteristic missing!")
                return
            }

            enableNotifyQueued(gatt, tx, "UART_TX")

            // Start descriptor queue
            writeNextDescriptor(gatt)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun enableNotifyQueued(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            label: String
        ) {
            Log.d("MicrobitBT", "Enabling notify for $label (${char.uuid})")

            gatt.setCharacteristicNotification(char, true)

            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.e("MicrobitBT", "Missing CCCD for $label")
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            descriptorWriteQueue.add(cccd)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun writeNextDescriptor(gatt: BluetoothGatt) {
            if (descriptorWriteQueue.isEmpty()) return

            val descriptor = descriptorWriteQueue.first()
            val ok = gatt.writeDescriptor(descriptor)

            Log.d("MicrobitBT", "writeDescriptor ${descriptor.uuid} ok=$ok")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d("MicrobitBT", "Descriptor written: ${descriptor.uuid}")

            if (descriptorWriteQueue.isNotEmpty() &&
                descriptorWriteQueue.first() == descriptor
            ) {
                descriptorWriteQueue.removeFirst()
            }

            writeNextDescriptor(gatt)
        }

        // ------------------------------------------------------
        // DATA RECEIVED FROM micro:bit UART
        // ------------------------------------------------------
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UART_TX_CHAR_UUID) {
                val msg = characteristic.getStringValue(0)
                Log.d("MicrobitBT", "UART message received: $msg")
                if (msg != null) onCommand(msg)
            }
        }
    }

    // ----------------------------------------------------------
    // Disconnect
    // ----------------------------------------------------------
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }
}
