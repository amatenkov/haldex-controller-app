package com.haldex.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Стандартный дескриптор подписки на уведомления BLE
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private val rxBuffer = StringBuilder()

    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvSlip: TextView
    private lateinit var tvLock: TextView
    private lateinit var tvExtraInfo: TextView
    private lateinit var graphView: GraphView

    private lateinit var btnEco: Button
    private lateinit var btnAuto: Button
    private lateinit var btnAwd: Button
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var btnPump: Button
    private lateinit var btnKill: Button
    private lateinit var btnReset: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvTemp = findViewById(R.id.tvTemp)
        tvSlip = findViewById(R.id.tvSlip)
        tvLock = findViewById(R.id.tvLock)
        tvExtraInfo = findViewById(R.id.tvExtraInfo)
        graphView = findViewById(R.id.graphView)

        btnEco = findViewById(R.id.btnEco)
        btnAuto = findViewById(R.id.btnAuto)
        btnAwd = findViewById(R.id.btnAwd)
        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)
        btnPump = findViewById(R.id.btnPump)
        btnKill = findViewById(R.id.btnKill)
        btnReset = findViewById(R.id.btnReset)

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            checkPermissionsAndShowDevicePicker()
        }

        btnEco.setOnClickListener { sendCommand("E") }
        btnAuto.setOnClickListener { sendCommand("A") }
        btnAwd.setOnClickListener { sendCommand("W") }
        btnMinus.setOnClickListener { sendCommand("-") }
        btnPlus.setOnClickListener { sendCommand("+") }
        btnPump.setOnClickListener { sendCommand("P") }
        btnKill.setOnClickListener { sendCommand("K") }
        btnReset.setOnClickListener { sendCommand("R") }
    }

    private fun checkPermissionsAndShowDevicePicker() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(android.Manifest.permission.BLUETOOTH)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val needed = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            showDeviceSelectionDialog()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            showDeviceSelectionDialog()
        } else {
            Toast.makeText(this, "Разрешите доступ к Bluetooth!", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()

        if (pairedDevices.isEmpty()) {
            tvStatus.text = "Нет сопряженных BLE устройств!"
            tvStatus.setTextColor(Color.parseColor("#FF1744"))
            Toast.makeText(this, "Спарьте HC-06 в настройках телефона (PIN 1234)!", Toast.LENGTH_LONG).show()
            return
        }

        val deviceList = pairedDevices.toList()
        val deviceNames = deviceList.map { "${it.name ?: "HC-06 (BLE)"}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите ваш BLE модуль:")
            .setItems(deviceNames) { _, which ->
                connectToBleDevice(deviceList[which])
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToBleDevice(device: BluetoothDevice) {
        disconnect()

        val devName = device.name ?: device.address
        tvStatus.text = "Подключение по BLE к $devName..."
        tvStatus.setTextColor(Color.parseColor("#FFD600"))

        // Подключаемся строго через Bluetooth Low Energy стек (TRANSPORT_LE)
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "ПОДКЛЮЧЕНО (BLE). Поиск каналов..."
                    tvStatus.setTextColor(Color.parseColor("#FFD600"))
                }
                // Ищем доступные GATT-сервисы
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvStatus.text = "Связь потеряна!"
                    tvStatus.setTextColor(Color.parseColor("#FF1744"))
                }
                disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                var notifyChar: BluetoothGattCharacteristic? = null

                // Универсальный поиск характеристик UART на любых BLE-чипах
                for (service in gatt.services) {
                    for (ch in service.characteristics) {
                        val props = ch.properties
                        // Поиск канала чтения (Notify / Indicate)
                        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)) {
                            notifyChar = ch
                        }
                        // Поиск канала записи команд (Write / Write Without Response)
                        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {
                            writeCharacteristic = ch
                        }
                    }
                }

                if (notifyChar != null) {
                    // Включаем подписку на приём данных
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }

                    isConnected = true
                    runOnUiThread {
                        tvStatus.text = "ПОДКЛЮЧЕНО (BLE): ${gatt.device.name ?: "HC-06"}"
                        tvStatus.setTextColor(Color.parseColor("#00E676"))
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "Ошибка: UART канал не найден"
                        tvStatus.setTextColor(Color.parseColor("#FF1744"))
                    }
                }
            }
        }

        // Обработка данных для старых версий Android
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            handleIncomingBytes(data)
        }

        // Обработка данных для Android 13+ (API 33+)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingBytes(value)
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val str = String(bytes)
        rxBuffer.append(str)

        if (rxBuffer.contains("\n")) {
            val lines = rxBuffer.toString().split("\n")
            for (i in 0 until lines.size - 1) {
                val trimmed = lines[i].trim()
                if (trimmed.isNotEmpty()) {
                    parseTelemetry(trimmed)
                }
            }
            rxBuffer.clear()
            rxBuffer.append(lines.last())
        }
    }

    private fun parseTelemetry(line: String) {
        if (!line.startsWith("\$HDX") || !line.endsWith("*")) return
        val payload = line.removePrefix("\$HDX,").removeSuffix("*")
        val data = payload.split(",")
        if (data.size < 9) return

        runOnUiThread {
            try {
                val mode = data[0].toInt()
                val lock = data[1].toInt()
                val actualCurrent = data[2].toFloat()
                val targetCurrent = data[3].toFloat()
                val temp = data[4].toInt()
                val speed = data[5].toInt()
                val slip = data[6].toFloat()
                val brake = data[7].toInt()
                val pump = data[8].toInt()

                tvSpeed.text = "$speed км/ч"
                tvTemp.text = "$temp °C"
                tvSlip.text = String.format("%.1f км/ч", slip)
                tvLock.text = "$lock%"

                tvExtraInfo.text = "Тормоз: " + (if (brake == 1) "АКТИВЕН" else "ОТПУЩЕН") + 
                                  " | Насос: " + (if (pump == 1) "ВКЛ (5V)" else "ВЫКЛ (0V)")

                btnEco.setBackgroundColor(if (mode == 0) Color.parseColor("#00E676") else Color.parseColor("#2E334D"))
                btnAuto.setBackgroundColor(if (mode == 1) Color.parseColor("#00E5FF") else Color.parseColor("#2E334D"))
                btnAwd.setBackgroundColor(if (mode == 2) Color.parseColor("#FFD600") else Color.parseColor("#2E334D"))
                
                btnEco.setTextColor(if (mode == 0) Color.parseColor("#0F111A") else Color.parseColor("#FFFFFF"))
                btnAuto.setTextColor(if (mode == 1) Color.parseColor("#0F111A") else Color.parseColor("#FFFFFF"))
                btnAwd.setTextColor(if (mode == 2) Color.parseColor("#0F111A") else Color.parseColor("#FFFFFF"))

                graphView.addPoints(targetCurrent, actualCurrent)

            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(cmd: String) {
        val ch = writeCharacteristic
        val gatt = bluetoothGatt
        if (!isConnected || ch == null || gatt == null) {
            Toast.makeText(this, "Нет связи с модулем!", Toast.LENGTH_SHORT).show()
            return
        }

        val bytes = cmd.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            ch.value = bytes
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            gatt.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        isConnected = false
        writeCharacteristic = null
        try { bluetoothGatt?.disconnect() } catch (ignored: Exception) {}
        try { bluetoothGatt?.close() } catch (ignored: Exception) {}
        bluetoothGatt = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
