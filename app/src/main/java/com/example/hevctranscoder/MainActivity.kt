package com.example.hevctranscoder

import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.deepmedia.transcoder.Transcoder
import io.deepmedia.transcoder.TranscoderListener
import io.deepmedia.transcoder.strategy.DefaultVideoStrategy
import io.deepmedia.transcoder.strategy.size.AtMostResizer
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var txtLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        btnStart = Button(this).apply { text = "URUCHOM KODOWANIE SPRZĘTOWE (HEVC)" }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        txtLog = TextView(this).apply { text = "Gotowy do pracy...\n" }

        layout.addView(btnStart)
        layout.addView(progressBar)
        layout.addView(txtLog)
        setContentView(layout)

        btnStart.setOnClickListener { startTranscodingBatch() }
    }

    private fun startTranscodingBatch() {
        btnStart.isEnabled = false
        val cameraDir = File(Environment.getExternalStorageDirectory(), "DCIM/Camera")
        val files = cameraDir.listFiles { _, name ->
            name.endsWith(".mp4", ignoreCase = true) && !name.contains("_hevc")
        } ?: emptyArray()

        if (files.isEmpty()) {
            txtLog.append("\n[INFO] Brak plików w DCIM/Camera.")
            btnStart.isEnabled = true
            return
        }
        processFile(files, 0)
    }

    private fun processFile(files: Array<File>, index: Int) {
        if (index >= files.size) {
            txtLog.append("\n=== ZAKOŃCZONO WSZYSTKIE PLIKI ===")
            btnStart.isEnabled = true
            return
        }

        val file = files[index]
        val outFile = File(file.parent, "${file.nameWithoutExtension}_hevc.mp4")
        txtLog.append("\n[${index + 1}/${files.size}] Kodowanie: ${file.name}")

        val strategy = DefaultVideoStrategy.Builder()
            .addResizer(AtMostResizer(1920))
            .bitRate(2500000L)
            .build()

        Transcoder.into(outFile.absolutePath)
            .addDataSource(file.absolutePath)
            .setVideoTrackStrategy(strategy)
            .setListener(object : TranscoderListener {
                override fun onTranscodeProgress(progress: Double) {
                    progressBar.progress = (progress * 100).toInt()
                }

                override fun onTranscodeCompleted(successCode: Int) {
                    val oldMb = file.length() / (1024 * 1024)
                    val newMb = outFile.length() / (1024 * 1024)

                    if (outFile.length() <= file.length() * 0.6) {
                        file.delete()
                        txtLog.append("\n [OK] Sukces: ${oldMb}MB -> ${newMb}MB")
                    } else {
                        outFile.delete()
                        txtLog.append("\n [!] Odrzucono: Brak zysku > 40%")
                    }
                    processFile(files, index + 1)
                }

                override fun onTranscodeFailed(exception: Throwable) {
                    txtLog.append("\n [BŁĄD] ${exception.localizedMessage}")
                    if (outFile.exists()) outFile.delete()
                    processFile(files, index + 1)
                }
            }).transcode()
    }
}
