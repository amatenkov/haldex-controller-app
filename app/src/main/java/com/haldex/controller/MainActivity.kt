package com.haldex.controller

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val btUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
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

        // Связываем элементы интерфейса
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
            checkPermissionsAndConnect()
        }

        // Слушатели кнопок отправки команд
        btnEco.setOnClickListener { sendCommand("E") }
        btnAuto.setOnClickListener { sendCommand("A") }
        btnAwd.setOnClickListener { sendCommand("W") }
        btnMinus.setOnClickListener { sendCommand("-") }
        btnPlus.setOnClickListener { sendCommand("+") }
        btnPump.setOnClickListener { sendCommand("P") }
        btnKill.setOnClickListener { sendCommand("K") }
        btnReset.setOnClickListener { sendCommand("R") }

        checkPermissionsAndConnect()
    }

    private fun checkPermissionsAndConnect() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val needed = permissions.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            connectToBluetooth()
        }
    }

    private fun connectToBluetooth() {
        if (isConnected) return
        tvStatus.text = "Поиск HC-06..."
        tvStatus.setTextColor(Color.parseColor("#FFD600"))

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val paired: Set<BluetoothDevice>? = try {
            adapter.bondedDevices
        } catch (e: SecurityException) {
            null
        }

        val targetDevice = paired?.find { it.name == "HC-06" || it.name == "HC-05" }

        if (targetDevice == null) {
            tvStatus.text = "Модуль HC-06 не найден в сопряженных!"
            tvStatus.setTextColor(Color.parseColor("#FF1744"))
            return
        }

        Thread {
            try {
                btSocket = targetDevice.createRfcommSocketToServiceRecord(btUuid)
                btSocket?.connect()
                outStream = btSocket?.outputStream
                inStream = btSocket?.inputStream
                isConnected = true

                runOnUiThread {
                    tvStatus.text = "ПОДКЛЮЧЕНО К HC-06"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                    startRxLoop()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Ошибка подключения!"
                    tvStatus.setTextColor(Color.parseColor("#FF1744"))
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
        if (!line.startsWith("$HDX") || !line.endsWith("*")) return
        // Убираем маркеры пакета и разбиваем по запятой
        val payload = line.replace("$HDX,", "").replace("*", "")
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

                // Обновление интерфейса
                tvSpeed.text = "$speed км/ч"
                tvTemp.text = "$temp °C"
                tvSlip.text = String.format("%.1f км/ч", slip)
                tvLock.text = "$lock%"

                tvExtraInfo.text = "Тормоз: " + (if (brake == 1) "АКТИВЕН" else "ОТПУЩЕН") + 
                                  " | Насос: " + (if (pump == 1) "ВКЛ (5V)" else "ВЫКЛ (0V)")

                // Подсветка кнопок текущего режима
                btnEco.setBackgroundColor(if (mode == 0) Color.parseColor("#00E676") else Color.parseColor("#2E334D"))
                btnAuto.setBackgroundColor(if (mode == 1) Color.parseColor("#00E5FF") else Color.parseColor("#2E334D"))
                btnAwd.setBackgroundColor(if (mode == 2) Color.parseColor("#FFD600") else Color.parseColor("#2E334D"))
                
                btnEco.setTextColor(if (mode == 0) Color.parseColor("#0F111A") else Color.white)
                btnAuto.setTextColor(if (mode == 1) Color.parseColor("#0F111A") else Color.white)
                btnAwd.setTextColor(if (mode == 2) Color.parseColor("#0F111A") else Color.white)

                // Добавление точек на осциллограф
                graphView.addPoints(targetCurrent, actualCurrent)

            } catch (e: Exception) {
                // Ошибка парсинга отдельной строки
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
            } catch (e: Exception) {
                // Ошибка отправки
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        try {
            btSocket?.close()
        } catch (e: Exception) {}
    }
}
