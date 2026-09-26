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
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val ffe1Uuid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val nusRxUuid: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private val rxBuffer = StringBuilder()

    private var isAutoBlocked = false
    private var currentMode = 0

    private var currentScreenIndex = 0
    private val totalScreens = 5
    private val screenTitles = arrayOf(
        "1/5 ГРАФИК ТОКА",
        "2/5 КОЛЁСА КРУПНО",
        "3/5 CAN ТЕЛЕМЕТРИЯ",
        "4/5 ДИАГНОСТИКА",
        "5/5 НАСТРОЙКА AWD"
    )

    private lateinit var tvStatus: TextView
    private lateinit var tvTitleView: TextView
    private lateinit var btnPrevScreen: Button
    private lateinit var btnNextScreen: Button
    private lateinit var carouselContainer: FrameLayout

    private lateinit var graphView: GraphView
    private lateinit var pageWheels: View
    private lateinit var pageTelemetry: View
    private lateinit var pageDiagnostic: View
    private lateinit var pageAwdSetup: View

    private lateinit var tvFl: TextView
    private lateinit var tvFr: TextView
    private lateinit var tvRl: TextView
    private lateinit var tvRr: TextView
    private lateinit var tvWheelSlip: TextView

    private lateinit var tvCanSpeed: TextView
    private lateinit var tvCanRpm: TextView
    private lateinit var tvCanGas: TextView
    private lateinit var tvCanSteer: TextView
    private lateinit var tvCanBrake: TextView
    private lateinit var tvCanPwm: TextView

    private lateinit var tvDiagHydr: TextView
    private lateinit var tvDiagCan: TextView
    private lateinit var tvDiagOil: TextView
    private lateinit var tvDiagPump: TextView
    private lateinit var tvDiagCut: TextView
    private lateinit var tvDiagCurrent: TextView

    private lateinit var tvAwdLockValue: TextView
    private lateinit var tvAwdPressure: TextView
    private lateinit var tvAwdAmps: TextView

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
        tvTitleView = findViewById(R.id.tvScreenTitle)
        btnPrevScreen = findViewById(R.id.btnPrevScreen)
        btnNextScreen = findViewById(R.id.btnNextScreen)
        carouselContainer = findViewById(R.id.carouselContainer)

        graphView = findViewById(R.id.graphView)
        pageWheels = findViewById(R.id.pageWheels)
        pageTelemetry = findViewById(R.id.pageTelemetry)
        pageDiagnostic = findViewById(R.id.pageDiagnostic)
        pageAwdSetup = findViewById(R.id.pageAwdSetup)

        tvFl = findViewById(R.id.tvFl)
        tvFr = findViewById(R.id.tvFr)
        tvRl = findViewById(R.id.tvRl)
        tvRr = findViewById(R.id.tvRr)
        tvWheelSlip = findViewById(R.id.tvWheelSlip)

        tvCanSpeed = findViewById(R.id.tvCanSpeed)
        tvCanRpm = findViewById(R.id.tvCanRpm)
        tvCanGas = findViewById(R.id.tvCanGas)
        tvCanSteer = findViewById(R.id.tvCanSteer)
        tvCanBrake = findViewById(R.id.tvCanBrake)
        tvCanPwm = findViewById(R.id.tvCanPwm)

        tvDiagHydr = findViewById(R.id.tvDiagHydr)
        tvDiagCan = findViewById(R.id.tvDiagCan)
        tvDiagOil = findViewById(R.id.tvDiagOil)
        tvDiagPump = findViewById(R.id.tvDiagPump)
        tvDiagCut = findViewById(R.id.tvDiagCut)
        tvDiagCurrent = findViewById(R.id.tvDiagCurrent)

        tvAwdLockValue = findViewById(R.id.tvAwdLockValue)
        tvAwdPressure = findViewById(R.id.tvAwdPressure)
        tvAwdAmps = findViewById(R.id.tvAwdAmps)

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

        btnPrevScreen.setOnClickListener { switchScreen((currentScreenIndex - 1 + totalScreens) % totalScreens) }
        btnNextScreen.setOnClickListener { switchScreen((currentScreenIndex + 1) % totalScreens) }

        setupSwipeDetector()

        btnEco.setOnClickListener { sendCommand("E") }
        btnAuto.setOnClickListener { 
            if (isAutoBlocked) {
                Toast.makeText(this, "Режим AUTO недоступен в аварии!", Toast.LENGTH_SHORT).show()
            } else {
                sendCommand("A") 
            }
        }
        btnAwd.setOnClickListener { sendCommand("W") }
        
        // Кнопки регулировки: если не в AWD, сначала включаем AWD
        btnMinus.setOnClickListener { 
            if (currentMode != 2) sendCommand("W")
            sendCommand("-") 
        }
        btnPlus.setOnClickListener { 
            if (currentMode != 2) sendCommand("W")
            sendCommand("+") 
        }

        btnPump.setOnClickListener { sendCommand("P") }
        btnKill.setOnClickListener { sendCommand("K") }
        btnReset.setOnClickListener { sendCommand("R") }
    }

    private fun switchScreen(index: Int) {
        currentScreenIndex = index
        tvTitleView.text = screenTitles[currentScreenIndex]

        graphView.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageWheels.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageTelemetry.visibility = if (index == 2) View.VISIBLE else View.GONE
        pageDiagnostic.visibility = if (index == 3) View.VISIBLE else View.GONE
        pageAwdSetup.visibility = if (index == 4) View.VISIBLE else View.GONE
    }

    private fun setupSwipeDetector() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > 80 && Math.abs(velocityX) > 100) {
                    if (diffX < 0) {
                        switchScreen((currentScreenIndex + 1) % totalScreens)
                    } else {
                        switchScreen((currentScreenIndex - 1 + totalScreens) % totalScreens)
                    }
                    return true
                }
                return false
            }
        })

        carouselContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
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
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val pairedDevices: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()

        if (pairedDevices.isEmpty()) {
            tvStatus.text = "Нет сопряженных BLE устройств!"
            tvStatus.setTextColor(Color.parseColor("#FF1744"))
            Toast.makeText(this, "Спарьте модуль в настройках Android!", Toast.LENGTH_LONG).show()
            return
        }

        val deviceList = pairedDevices.toList()
        val deviceNames = deviceList.map { "${it.name ?: "BLE Module"}\n${it.address}" }.toTypedArray()

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
        tvStatus.text = "Подключение к $devName..."
        tvStatus.setTextColor(Color.parseColor("#FFD600"))

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
                    tvStatus.text = "ПОДКЛЮЧЕНО. Поиск каналов..."
                    tvStatus.setTextColor(Color.parseColor("#FFD600"))
                }
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
                var writeChar: BluetoothGattCharacteristic? = null

                for (service in gatt.services) {
                    val sUuid = service.uuid.toString().lowercase()
                    if (sUuid.startsWith("00001800") || sUuid.startsWith("00001801") || sUuid.startsWith("0000180a")) continue

                    for (ch in service.characteristics) {
                        if (ch.uuid == ffe1Uuid || ch.uuid == nusRxUuid) {
                            writeChar = ch
                        }
                        val props = ch.properties
                        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)) {
                            notifyChar = ch
                        }
                    }
                }

                if (writeChar == null) {
                    for (service in gatt.services) {
                        val sUuid = service.uuid.toString().lowercase()
                        if (sUuid.startsWith("00001800") || sUuid.startsWith("00001801") || sUuid.startsWith("0000180a")) continue
                        for (ch in service.characteristics) {
                            val props = ch.properties
                            if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {
                                writeChar = ch
                                break
                            }
                        }
                        if (writeChar != null) break
                    }
                }

                writeCharacteristic = writeChar

                if (notifyChar != null && writeCharacteristic != null) {
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
                        tvStatus.text = "ГОТОВО: ${gatt.device.name ?: "BLE"}"
                        tvStatus.setTextColor(Color.parseColor("#00E676"))
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "Ошибка: не найден UART канал"
                        tvStatus.setTextColor(Color.parseColor("#FF1744"))
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            handleIncomingBytes(data)
        }

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
        val d = payload.split(",")
        if (d.size < 21) return

        runOnUiThread {
            try {
                currentMode = d[0].toInt()
                val targetLock = d[1].toInt()
                val measuredCurrentA = d[2].toFloat()
                val targetCurrentA = d[3].toFloat()
                val currentTempC = d[4].toInt()
                val tempRaw = d[5].toInt()
                val vehicleSpeed = d[6].toInt()
                val engineRPM = d[7].toInt()
                val pedalPercent = d[8].toInt()
                val slipDiff = d[9].toFloat()
                val steerAngle = d[10].toFloat()
                val isBraking = d[11].toInt() == 1
                val isPumpEnabled = d[12].toInt() == 1

                val flSpeed = d[13].toFloat()
                val frSpeed = d[14].toFloat()
                val rlSpeed = d[15].toFloat()
                val rrSpeed = d[16].toFloat()

                val hydrFault = d[17].toInt() == 1
                val canOnline = d[18].toInt() == 1
                val emergencyCut = d[19].toInt() == 1
                val currentPWM = d[20].toInt()

                isAutoBlocked = (!canOnline || emergencyCut || hydrFault)

                // 1. График
                graphView.addPoints(targetCurrentA, measuredCurrentA)

                // 2. Колёса
                tvFl.text = String.format("%.1f км/ч", flSpeed)
                tvFr.text = String.format("%.1f км/ч", frSpeed)
                tvRl.text = String.format("%.1f км/ч", rlSpeed)
                tvRr.text = String.format("%.1f км/ч", rrSpeed)
                tvWheelSlip.text = String.format("БУКС: %+.1f км/ч", slipDiff)

                // 3. CAN телеметрия
                tvCanSpeed.text = "Скорость: $vehicleSpeed км/ч"
                tvCanRpm.text = "Обороты: $engineRPM RPM"
                tvCanGas.text = "Педаль газа: $pedalPercent%"
                tvCanSteer.text = "Угол руля: $steerAngle°"
                tvCanBrake.text = "Педаль тормоза: " + (if (isBraking) "АКТИВЕН" else "ОТПУЩЕН")
                tvCanBrake.setTextColor(if (isBraking) Color.parseColor("#FF1744") else Color.parseColor("#00E676"))
                tvCanPwm.text = "ШИМ клапана N373: $currentPWM / 255"

                // 4. Диагностика
                tvDiagHydr.text = if (hydrFault) "Гидравлика: ! АВАРИЯ ДАВЛЕНИЯ" else "Гидравлика: OK"
                tvDiagHydr.setTextColor(if (hydrFault) Color.parseColor("#FF1744") else Color.parseColor("#00E676"))

                tvDiagCan.text = if (canOnline) "CAN-шина: ONLINE" else "CAN-шина: ! НЕТ ДАННЫХ"
                tvDiagCan.setTextColor(if (canOnline) Color.parseColor("#00E676") else Color.parseColor("#FF1744"))

                tvDiagOil.text = "Температура масла: $currentTempC °C (R: $tempRaw)"
                tvDiagPump.text = if (isPumpEnabled) "Насос V181: ВКЛ" else "Насос V181: ВЫКЛ"
                tvDiagCut.text = if (emergencyCut) "Отсечки: ! EMERGENCY CUT" else "Отсечки: НЕТ"
                tvDiagCurrent.text = String.format("Ток соленоида: %.2f А (Цель: %.2f А)", measuredCurrentA, targetCurrentA)

                // 5. Настройка AWD
                tvAwdLockValue.text = "$targetLock%"
                tvAwdPressure.text = String.format("P(est): ~%.1f bar", (targetLock / 100.0) * 35.0)
                tvAwdAmps.text = String.format("Ток: %.2f А", targetCurrentA)

                // =========================================================
                // НАДЁЖНАЯ ПОДСВЕТКА КНОПОК РЕЖИМОВ (ЧЕРЕЗ backgroundTintList)
                // =========================================================
                // Кнопка ECO
                btnEco.backgroundTintList = ColorStateList.valueOf(
                    if (currentMode == 0) Color.parseColor("#00E676") else Color.parseColor("#2E334D")
                )
                btnEco.setTextColor(if (currentMode == 0) Color.parseColor("#0F111A") else Color.WHITE)

                // Кнопка AUTO
                if (isAutoBlocked) {
                    btnAuto.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#151722"))
                    btnAuto.setTextColor(Color.parseColor("#4A4D5E"))
                } else {
                    btnAuto.backgroundTintList = ColorStateList.valueOf(
                        if (currentMode == 1) Color.parseColor("#00E5FF") else Color.parseColor("#2E334D")
                    )
                    btnAuto.setTextColor(if (currentMode == 1) Color.parseColor("#0F111A") else Color.WHITE)
                }

                // Кнопка AWD
                btnAwd.backgroundTintList = ColorStateList.valueOf(
                    if (currentMode == 2) Color.parseColor("#FFD600") else Color.parseColor("#2E334D")
                )
                btnAwd.setTextColor(if (currentMode == 2) Color.parseColor("#0F111A") else Color.WHITE)

                // =========================================================
                // ПОДСВЕТКА КНОПКИ НАСОСА (ВКЛ = ЗЕЛЕНЫЙ, ВЫКЛ = ТЕМНЫЙ)
                // =========================================================
                if (isPumpEnabled) {
                    btnPump.text = "НАСОС: ВКЛ (РАБОТАЕТ)"
                    btnPump.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                    btnPump.setTextColor(Color.parseColor("#0F111A"))
                } else {
                    btnPump.text = "НАСОС: ВЫКЛ"
                    btnPump.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1B1E2E"))
                    btnPump.setTextColor(Color.parseColor("#8A8D9F"))
                }

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

        val bytes = cmd.toByteArray(Charsets.UTF_8)
        val props = ch.properties
        val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, bytes, writeType)
        } else {
            ch.value = bytes
            ch.writeType = writeType
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
