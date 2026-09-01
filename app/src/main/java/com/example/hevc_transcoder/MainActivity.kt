package com.example.hevc_transcoder

import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.pow

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
    private var isProcessing = false

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

        btnStart.setOnClickListener {
            startBatchProcessing()
        }

        btnStop.setOnClickListener {
            stopBatchProcessing()
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            tvLogConsole.append("$message\n")
            scrollViewLogs.post { scrollViewLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun calculateTargetBitrate(width: Int, height: Int): Int {
        val pixels = width.toDouble() * height.toDouble()
        return (pixels.pow(0.75) * 0.05).toInt()
    }

    private fun startBatchProcessing() {
        isProcessing = true
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        appendLog("> Rozpoczęto skanowanie: ${currentFolder.absolutePath}")
    }

    private fun stopBatchProcessing() {
        isProcessing = false
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        appendLog("> Zatrzymano na żądanie użytkownika.")
    }
}
