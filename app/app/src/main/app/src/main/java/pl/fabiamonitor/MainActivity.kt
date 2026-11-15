package pl.fabiamonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val sppUuid: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var worker: Thread? = null
    private var connected = false

    private lateinit var status: TextView
    private lateinit var coolant: TextView
    private lateinit var instant: TextView
    private lateinit var average: TextView
    private lateinit var voltage: TextView
    private lateinit var errors: TextView

    private var fuelLitres = 0.0
    private var distanceKm = 0.0
    private var lastSampleTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    try {
        createScreen()

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                100
            )
        } else {
            connectToAdapter()
        }
    } catch (e: Exception) {
        val message = TextView(this).apply {
            text = "Błąd uruchamiania:\n${e.javaClass.simpleName}\n${e.message}"
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }
        setContentView(message)
    }
}


    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF101820.toInt())
        }

        fun heading(text: String, size: Float): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(8, 12, 8, 12)
            }
        }

        fun value(): TextView = heading("--", 27f)

        root.addView(heading("FABIA MONITOR", 26f))
        status = heading("Łączenie z OBDII...", 16f)
        root.addView(status)

        root.addView(heading("Temperatura płynu chłodzącego", 17f))
        coolant = value()
        root.addView(coolant)

        root.addView(heading("Chwilowe zużycie paliwa", 17f))
        instant = value()
        root.addView(instant)

        root.addView(heading("Średnie zużycie paliwa – sesja", 17f))
        average = value()
        root.addView(average)

        root.addView(heading("Napięcie adaptera", 17f))
        voltage = value()
        root.addView(voltage)

        val readButton = Button(this).apply {
            text = "Odczytaj błędy OBD2"
            setOnClickListener { readErrors() }
        }
        root.addView(readButton)

        val clearButton = Button(this).apply {
            text = "Kasuj błędy OBD2"
            setOnClickListener { confirmClearErrors() }
        }
        root.addView(clearButton)

        errors = heading("Błędy: jeszcze nie odczytano", 15f)
        root.addView(errors)

        root.addView(
            heading(
                "Średnia jest liczona przez aplikację od chwili połączenia. " +
                    "Wskazania zależą od możliwości adaptera i sterownika.",
                13f
            )
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                connectToAdapter()
            } else {
                status.text = "Brak zgody na Bluetooth. Włącz ją w ustawieniach."
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToAdapter() {
        val adapter = BluetoothAdapter.getDefaultAdapter()

        if (adapter == null) {
            status.text = "Telefon nie obsługuje Bluetooth."
            return
        }

        if (!adapter.isEnabled) {
            status.text = "Włącz Bluetooth w telefonie."
            return
        }

        worker = thread {
            try {
                val device = adapter.bondedDevices.firstOrNull {
                    val name = it.name?.uppercase(Locale.ROOT) ?: ""
                    name.contains("OBD") || name.contains("ELM")
                }

                if (device == null) {
                    updateStatus(
                        "Nie znaleziono sparowanego OBDII. " +
                            "Sparuj adapter w ustawieniach Bluetooth."
                    )
                    return@thread
                }

                updateStatus("Łączenie z ${device.name}...")

                val newSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                socket = newSocket
                newSocket.connect()

                input = newSocket.inputStream
                output = newSocket.outputStream

                sendCommand("ATZ")
                Thread.sleep(1200)
                sendCommand("ATE0")
                sendCommand("ATL0")
                sendCommand("ATS0")
                sendCommand("ATH0")
                sendCommand("ATSP0")

                connected = true
                updateStatus("Połączono z ${device.name}")

                while (connected) {
                    val coolantResponse = sendCommand("0105")
                    val speedResponse = sendCommand("010D")
                    val mafResponse = sendCommand("0110")
                    val voltageResponse = sendCommand("ATRV")

                    val temp = parsePid(coolantResponse, "4105", 1)
                    val speed = parsePid(speedResponse, "410D", 1)
                    val maf = parsePid(mafResponse, "4110", 2)

                    val volts = Regex("""(\d+(?:\.\d+)?)\s*V""")
                        .find(voltageResponse)?.groupValues?.get(1)

                    if (temp != null) {
                        runOnUiThread {
                            coolant.text = "${temp.toInt()} °C"
                        }
                    }

                    if (volts != null) {
                        runOnUiThread {
                            voltage.text = "$volts V"
                        }
                    }

                    if (speed != null && maf != null) {
                        updateFuelConsumption(speed, maf)
                    }

                    Thread.sleep(1200)
                }
            } catch (e: Exception) {
                connected = false
                updateStatus("Połączenie przerwane: ${e.message ?: "błąd"}")
            }
        }
    }

    private fun updateFuelConsumption(speedValue: Double, maf: Double) {
        val speedKmh = speedValue
        val fuelRateLph = maf * 3600.0 / (14.7 * 745.0)

        val instantValue =
            if (speedKmh > 3.0) fuelRateLph * 100.0 / speedKmh
            else null

        val now = System.currentTimeMillis()

        if (lastSampleTime > 0 && speedKmh > 3.0) {
            val hours = (now - lastSampleTime) / 3_600_000.0
            fuelLitres += fuelRateLph * hours
            distanceKm += speedKmh * hours
        }

        lastSampleTime = now

        val averageValue =
            if (distanceKm > 0.05) fuelLitres * 100.0 / distanceKm
            else null

        runOnUiThread {
            instant.text = instantValue?.let {
                String.format(Locale.getDefault(), "%.1f l/100 km", it)
            } ?: "--"

            average.text = averageValue?.let {
                String.format(Locale.getDefault(), "%.1f l/100 km", it)
            } ?: "--"
        }
    }

    private fun parsePid(response: String, header: String, bytes: Int): Double? {
        val clean = response
            .uppercase(Locale.ROOT)
            .replace(" ", "")
            .replace("\r", "")
            .replace("\n", "")

        val index = clean.indexOf(header)
        if (index < 0) return null

        val start = index + header.length
        if (clean.length < start + bytes * 2) return null

        return try {
            val a = clean.substring(start, start + 2).toInt(16)
            when (header) {
                "4105" -> (a - 40).toDouble()
                "410D" -> a.toDouble()
                "4110" -> {
                    val b = clean.substring(start + 2, start + 4).toInt(16)
                    (a * 256 + b) / 100.0
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun sendCommand(command: String): String {
        val out = output ?: return ""
        val source = input ?: return ""

        return try {
            out.write((command + "\r").toByteArray())
            out.flush()

            val result = StringBuilder()
            val start = System.currentTimeMillis()

            while (System.currentTimeMillis() - start < 2500) {
                if (source.available() > 0) {
                    val char = source.read()
                    if (char < 0) break
                    if (char.toChar() == '>') break
                    result.append(char.toChar())
                } else {
                    Thread.sleep(30)
                }
            }
            result.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun readErrors() {
        if (!connected) {
            errors.text = "Najpierw połącz się z adapterem OBDII."
            return
        }

        thread {
            val response = sendCommand("03")
            runOnUiThread {
                errors.text = if (response.contains("NO DATA", true)) {
                    "Nie wykryto błędów lub sterownik nie obsługuje odczytu."
                } else {
                    "Odpowiedź sterownika: ${response.trim()}"
                }
            }
        }
    }

    private fun confirmClearErrors() {
        AlertDialog.Builder(this)
            .setTitle("Kasowanie błędów")
            .setMessage(
                "Czy na pewno skasować standardowe błędy OBD2? " +
                    "Kasowanie nie naprawia usterki i może wyzerować monitory gotowości."
            )
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Kasuj") { _, _ ->
                if (connected) {
                    thread {
                        val response = sendCommand("04")
                        runOnUiThread {
                            errors.text = "Odpowiedź sterownika: ${response.trim()}"
                        }
                    }
                } else {
                    errors.text = "Brak połączenia z adapterem."
                }
            }
            .show()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            status.text = message
        }
    }

    override fun onDestroy() {
        connected = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
