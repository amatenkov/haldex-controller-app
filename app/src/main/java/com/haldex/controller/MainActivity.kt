package com.haldex.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var inStream: InputStream? = null
    private var isConnected = false
    private var rxThread: Thread? = null

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
            // Для сопряженных устройств нужен только CONNECT (без требования геолокации/SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(android.Manifest.permission.BLUETOOTH)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
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
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth не найден!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!adapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()

        if (pairedDevices.isEmpty()) {
            tvStatus.text = "Нет сопряжённых устройств в телефоне!"
            tvStatus.setTextColor(Color.parseColor("#FF1744"))
            Toast.makeText(this, "Спарьте HC-06 в настройках Android (PIN 1234)!", Toast.LENGTH_LONG).show()
            return
        }

        val deviceList = pairedDevices.toList()
        val deviceNames = deviceList.map { "${it.name ?: "HC-06"}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите ваш модуль:")
            .setItems(deviceNames) { _, which ->
                connectToDevice(deviceList[which])
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        disconnect()

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val devName = device.name ?: device.address

        tvStatus.text = "Соединение с $devName..."
        tvStatus.setTextColor(Color.parseColor("#FFD600"))

        Thread {
            try { adapter.cancelDiscovery() } catch (ignored: Exception) {}

            var socket: BluetoothSocket? = null
            var success = false

            // 1. Принудительное подключение к RFCOMM Channel 1 (обход бага сокета в HC-06)
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = m.invoke(device, 1) as BluetoothSocket
                socket.connect()
                success = true
            } catch (e1: Exception) {
                try { socket?.close() } catch (ignored: Exception) {}
                socket = null
            }

            // 2. Если прошивка телефона блокирует вызов канала, пробуем Insecure SPP
            if (!success) {
                try {
                    Thread.sleep(200)
                    socket = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
                    socket.connect()
                    success = true
                } catch (e2: Exception) {
                    try { socket?.close() } catch (ignored: Exception) {}
                    socket = null
                }
            }

            // 3. Последняя попытка через стандартный сокет
            if (!success) {
                try {
                    Thread.sleep(200)
                    socket = device.createRfcommSocketToServiceRecord(sppUuid)
                    socket.connect()
                    success = true
                } catch (e3: Exception) {
                    try { socket?.close() } catch (ignored: Exception) {}
                    socket = null
                    
                    isConnected = false
                    runOnUiThread {
                        tvStatus.text = "Ошибка: ${e3.localizedMessage ?: e3.message ?: "Таймаут сокета"}"
                        tvStatus.setTextColor(Color.parseColor("#FF1744"))
                    }
                    return@Thread
                }
            }

            if (success && socket != null) {
                btSocket = socket
                outStream = socket.outputStream
                inStream = socket.inputStream
                isConnected = true

                runOnUiThread {
                    tvStatus.text = "ПОДКЛЮЧЕНО: $devName"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                    startRxLoop()
                }
            }
        }.start()
    }

    private fun startRxLoop() {
        rxThread = Thread {
            val buffer = ByteArray(1024)
            var bytes: Int
            val builder = StringBuilder()

            while (isConnected) {
                try {
                    bytes = inStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val str = String(buffer, 0, bytes)
                        builder.append(str)
                        if (builder.contains("\n")) {
                            val lines = builder.toString().split("\n")
                            for (i in 0 until lines.size - 1) {
                                parseTelemetry(lines[i].trim())
                            }
                            builder.setLength(0)
                            builder.append(lines.last())
                        }
                    }
                } catch (e: Exception) {
                    isConnected = false
                    runOnUiThread {
                        tvStatus.text = "Связь потеряна!"
                        tvStatus.setTextColor(Color.parseColor("#FF1744"))
                    }
                }
            }
        }
        rxThread?.start()
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

    private fun sendCommand(cmd: String) {
        if (!isConnected) {
            Toast.makeText(this, "Нет связи с модулем!", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                outStream?.write(cmd.toByteArray())
                outStream?.flush()
            } catch (ignored: Exception) {
            }
        }.start()
    }

    private fun disconnect() {
        isConnected = false
        try { inStream?.close() } catch (ignored: Exception) {}
        try { outStream?.close() } catch (ignored: Exception) {}
        try { btSocket?.close() } catch (ignored: Exception) {}
        btSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
