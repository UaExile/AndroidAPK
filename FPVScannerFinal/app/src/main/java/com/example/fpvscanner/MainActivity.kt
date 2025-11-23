package com.example.fpvscanner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("hackrfbackend")
        }
    }

    // native-методи
    external fun nativeStartScan(): Boolean
    external fun nativeStopScan()
    external fun nativeIsDeviceConnected(): Boolean
    external fun nativeGetLastDetection(): String?
    external fun nativeTestBackend(): String
    external fun nativeSetBandMode(mode: Int)
    external fun nativeSetDetectionParams(ratio: Double)
    external fun nativeSetGain(lna: Int, vga: Int, ampOn: Boolean)

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var lastDetectionText: TextView
    private lateinit var logText: TextView
    private lateinit var statusText: TextView
    private lateinit var bandSpinner: Spinner
    private lateinit var btnOpenFolder: Button
    private lateinit var btnExportLog: Button

    // Елементи керування посиленням
    private lateinit var sbLnaGain: SeekBar
    private lateinit var sbVgaGain: SeekBar
    private lateinit var swAmp: Switch
    private lateinit var txtLnaGainValue: TextView
    private lateinit var txtVgaGainValue: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private lateinit var soundPool: SoundPool
    private var beepId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.btnStartScan)
        stopButton = findViewById(R.id.btnStopScan)
        lastDetectionText = findViewById(R.id.txtLastDetection)
        logText = findViewById(R.id.txtLog)
        statusText = findViewById(R.id.txtStatus)
        bandSpinner = findViewById(R.id.spinnerBand)
        btnOpenFolder = findViewById(R.id.btnOpenFolder)
        btnExportLog = findViewById(R.id.btnExportLog)

        // gain controls
        sbLnaGain = findViewById(R.id.sbLnaGain)
        sbVgaGain = findViewById(R.id.sbVgaGain)
        swAmp = findViewById(R.id.swAmp)
        txtLnaGainValue = findViewById(R.id.txtLnaGainValue)
        txtVgaGainValue = findViewById(R.id.txtVgaGainValue)

        setupSound()
        setupBandSpinner()
        setupGainControls()
        setupLogButtons()

        // Тест зв’язку з native
        try {
            val msg = nativeTestBackend()
            appendLog("Native backend OK: $msg")
        } catch (e: Throwable) {
            appendLog("Native backend error: ${e.message}")
        }

        startButton.setOnClickListener {
            onStartScanClicked()
        }

        stopButton.setOnClickListener {
            onStopScanClicked()
        }

        updateDeviceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        handler.removeCallbacksAndMessages(null)
        nativeStopScan()
        soundPool.release()
    }

    private fun setupSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attrs)
            .build()

        beepId = soundPool.load(this, R.raw.beep, 1)
    }

    private fun setupBandSpinner() {
        val bands = listOf("Auto", "1.2 GHz", "2.4 GHz", "3.3 GHz", "5.8 GHz")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bands)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bandSpinner.adapter = adapter

        bandSpinner.setSelection(0)

        bandSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                // 0=Auto, 1=1.2, 2=2.4, 3=3.3, 4=5.8 (синхронізовано з native)
                nativeSetBandMode(position)
                appendLog("Band set to ${bands[position]}, mode=$position")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupGainControls() {
        // Приклад: LNA 0..40, VGA 0..62 (в кроках 2 дБ – лише приклад)
        sbLnaGain.max = 40
        sbVgaGain.max = 62

        fun updateAndSend() {
            val lna = sbLnaGain.progress
            val vga = sbVgaGain.progress
            val amp = swAmp.isChecked

            txtLnaGainValue.text = "$lna dB"
            txtVgaGainValue.text = "$vga dB"

            // виклик у native-бік
            nativeSetGain(lna, vga, amp)
        }

        sbLnaGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAndSend()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbVgaGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAndSend()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        swAmp.setOnCheckedChangeListener { _, _ ->
            updateAndSend()
        }

        // початкове значення
        updateAndSend()
    }

    private fun setupLogButtons() {
        btnOpenFolder.setOnClickListener {
            // відкриваємо теку логів у файловому менеджері
            try {
                val uri = Uri.parse(getString(R.string.log_folder_uri))
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "resource/folder")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Не вдалося відкрити файловий менеджер", Toast.LENGTH_SHORT).show()
            }
        }

        btnExportLog.setOnClickListener {
            // тут твоя логіка експорту (яку ти вже робив)
        }
    }

    private fun onStartScanClicked() {
        appendLog("Start pressed, trying to start scan...")
        try {
            val ok = nativeStartScan()
            if (ok) {
                appendLog("Scan started.")
                updateDeviceStatus()
                startPolling()
            } else {
                appendLog("Failed to start scan (HackRF not connected or error).")
                updateDeviceStatus()
            }
        } catch (e: Throwable) {
            appendLog("Exception in nativeStartScan: ${e.message}")
        }
    }

    private fun onStopScanClicked() {
        appendLog("Stopping scan...")
        try {
            nativeStopScan()
        } catch (e: Throwable) {
            appendLog("Exception in nativeStopScan: ${e.message}")
        }
        stopPolling()
        updateDeviceStatus()
        appendLog("Scan stopped.")
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return

            updateDeviceStatus()

            try {
                val det = nativeGetLastDetection()
                if (det != null && det.isNotEmpty()) {
                    lastDetectionText.text = "Last detection: $det"
                    appendLog("Detection: $det")
                    soundPool.play(beepId, 1f, 1f, 1, 0, 1f)

                    // можна ще показувати AlertDialog тут
                }
            } catch (e: Throwable) {
                appendLog("Exception in nativeGetLastDetection: ${e.message}")
            }

            handler.postDelayed(this, 500)
        }
    }

    private fun updateDeviceStatus() {
        val connected = try {
            nativeIsDeviceConnected()
        } catch (_: Throwable) {
            false
        }
        if (connected) {
            statusText.text = "HackRF status: CONNECTED (via HackRF)"
            statusText.setTextColor(0xFF00FF00.toInt())
        } else {
            statusText.text = "HackRF status: NOT CONNECTED"
            statusText.setTextColor(0xFFFF5555.toInt())
        }
    }

    private fun appendLog(msg: String) {
        logText.text = buildString {
            append(logText.text)
            if (logText.text.isNotEmpty()) append("\n")
            append(msg)
        }
    }
}
