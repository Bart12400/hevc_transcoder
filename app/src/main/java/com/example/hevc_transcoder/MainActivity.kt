package com.example.hevc_transcoder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvFolderPath: TextView
    private lateinit var tvCurrentFileHeader: TextView
    private lateinit var tvCurrentFileDetails: TextView
    private lateinit var progressBarFile: ProgressBar
    private lateinit var tvFileSpeedEta: TextView
    private lateinit var tvLogConsole: TextView
    private lateinit var scrollViewLogs: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var currentFolder: File = File(Environment.getExternalStorageDirectory(), "DCIM/Camera")
    @Volatile private var isProcessing = false
    private var currentEngine: TranscoderEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvFolderPath = findViewById(R.id.tvFolderPath)
        tvCurrentFileHeader = findViewById(R.id.tvCurrentFileHeader)
        tvCurrentFileDetails = findViewById(R.id.tvCurrentFileDetails)
        progressBarFile = findViewById(R.id.progressBarFile)
        tvFileSpeedEta = findViewById(R.id.tvFileSpeedEta)
        tvLogConsole = findViewById(R.id.tvLogConsole)
        scrollViewLogs = findViewById(R.id.scrollViewLogs)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        tvFolderPath.text = currentFolder.absolutePath

        checkPermissions()

        btnStart.setOnClickListener { startBatchProcessing() }
        btnStop.setOnClickListener { stopBatchProcessing() }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            tvLogConsole.append("$message\n")
            scrollViewLogs.post { scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startBatchProcessing() {
        isProcessing = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        appendLog("> Skanowanie plikow w: ${currentFolder.absolutePath}")

        thread {
            val videoFiles = currentFolder.walkTopDown()
                .filter { it.isFile && (it.extension.equals("mp4", true) || it.extension.equals("mov", true)) }
                .filter { !it.name.contains("_HEVC") }
                .toList()

            if (videoFiles.isEmpty()) {
                appendLog("> Brak nowych plikow wideo do kompresji.")
                runOnUiThread { stopBatchProcessing() }
                return@thread
            }

            appendLog("> Znaleziono ${videoFiles.size} plikow do przetworzenia.")

            for ((index, file) in videoFiles.withIndex()) {
                if (!isProcessing) break

                val outFile = File(file.parentFile, "${file.nameWithoutExtension}_HEVC.mp4")
                
                runOnUiThread {
                    tvCurrentFileHeader.text = "[${index + 1}/${videoFiles.size}] ${file.name}"
                    tvCurrentFileDetails.text = "Rozmiar: ${file.length() / (1024 * 1024)} MB -> ${outFile.name}"
                    progressBarFile.progress = 0
                }

                appendLog("> Start: ${file.name}")

                val engine = TranscoderEngine(file, outFile) { progress, speed, etaSeconds ->
                    runOnUiThread {
                        progressBarFile.progress = (progress * 100).toInt()
                        tvFileSpeedEta.text = String.format("Postep: %d%% | Speed: %.2fx | ETA: %02d:%02d",
                            (progress * 100).toInt(), speed, etaSeconds / 60, etaSeconds % 60)
                    }
                }

                currentEngine = engine
                val success = engine.start()

                if (success) {
                    appendLog("  [OK] Zapisano: ${outFile.name}")
                } else if (isProcessing) {
                    appendLog("  [BLAD] Nie udalo sie skompresowac: ${file.name}")
                }
            }

            appendLog("> Zakonczono cale zadanie.")
            runOnUiThread { stopBatchProcessing() }
        }
    }

    private fun stopBatchProcessing() {
        isProcessing = false
        currentEngine?.cancel()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvCurrentFileHeader.text = "Status: Zatrzymano"
        appendLog("> Przerwano operacje.")
    }
}
