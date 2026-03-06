package com.example.talkingtimer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.example.talkingtimer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var audioManager: AudioManager

    private var selectedSongUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var countDownTimer: CountDownTimer? = null

    private var timerRunning = false
    private var paused = false
    private var remainingSeconds = 60
    private var previousMediaVolume: Int = -1

    private val toneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("timer_settings", Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        loadDefaults()
        loadSavedSong()

        binding.chooseSongButton.setOnClickListener { chooseSong() }
        binding.startButton.setOnClickListener { startTimer() }
        binding.pauseButton.setOnClickListener { pauseTimer() }
        binding.resetButton.setOnClickListener { resetTimer() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
    }

    private fun loadDefaults() {
        val defaultMinutes = prefs.getInt("default_minutes", 1)
        val defaultSeconds = prefs.getInt("default_seconds", 0)
        remainingSeconds = defaultMinutes * 60 + defaultSeconds
        binding.secondsLeftText.text = remainingSeconds.toString()
    }

    private fun loadSavedSong() {
        val savedUri = prefs.getString("song_uri", null)
        if (savedUri != null) {
            selectedSongUri = Uri.parse(savedUri)
            binding.chooseSongButton.text = "Music Selected"
        }
    }

    private fun chooseSong() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            selectedSongUri = uri

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            prefs.edit().putString("song_uri", uri.toString()).apply()
            binding.chooseSongButton.text = "Music Selected"
        }
    }

    private fun startTimer() {
        if (timerRunning) return

        if (!paused) {
            val defaultMinutes = prefs.getInt("default_minutes", 1)
            val defaultSeconds = prefs.getInt("default_seconds", 0)
            remainingSeconds = defaultMinutes * 60 + defaultSeconds
        }

        if (remainingSeconds <= 0) return

        ensureAudibleMediaVolume()
        startMusic()

        timerRunning = true
        paused = false

        binding.startButton.text = "RUNNING"

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remainingSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000L).toInt()
                binding.secondsLeftText.text = remainingSeconds.toString()

                val finalBeepsEnabled = prefs.getBoolean("final_beeps", true)

                if (remainingSeconds > 0 && remainingSeconds % 30 == 0) {
                    beepOnce()
                }

                if (finalBeepsEnabled && remainingSeconds in 1..10) {
                    beepOnce()
                }
            }

            override fun onFinish() {
                binding.secondsLeftText.text = "0"
                timerRunning = false
                paused = false
                binding.startButton.text = "START"
                stopMusic()
                restorePreviousMediaVolume()
                beepFiveTimes()
            }
        }.start()
    }

    private fun pauseTimer() {
        if (!timerRunning) return

        countDownTimer?.cancel()
        timerRunning = false
        paused = true
        stopMusic()
        binding.startButton.text = "START"
    }

    private fun resetTimer() {
        countDownTimer?.cancel()
        timerRunning = false
        paused = false
        stopMusic()
        restorePreviousMediaVolume()

        val defaultMinutes = prefs.getInt("default_minutes", 1)
        val defaultSeconds = prefs.getInt("default_seconds", 0)
        remainingSeconds = defaultMinutes * 60 + defaultSeconds
        binding.secondsLeftText.text = remainingSeconds.toString()
        binding.startButton.text = "START"
    }

    private fun startMusic() {
        val uri = selectedSongUri ?: return

        try {
            stopMusic()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
    }

    private fun stopMusic() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun beepOnce() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private fun beepFiveTimes() {
        Thread {
            repeat(5) {
                runOnUiThread { beepOnce() }
                Thread.sleep(250)
            }
        }.start()
    }

    private fun ensureAudibleMediaVolume() {
        val autoRaise = prefs.getBoolean("raise_volume", true)
        if (!autoRaise) return

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val targetPercent = prefs.getInt("start_volume_percent", 60).coerceIn(1, 100)

        if (previousMediaVolume == -1) {
            previousMediaVolume = currentVolume
        }

        val targetVolume = ((maxVolume * targetPercent) / 100.0).toInt().coerceAtLeast(1)

        if (currentVolume < targetVolume) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    private fun restorePreviousMediaVolume() {
        if (previousMediaVolume >= 0) {
            try {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    previousMediaVolume,
                    0
                )
            } catch (_: Exception) {
            }
            previousMediaVolume = -1
        }
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.settings_dialog, null)

        val defaultMinutes = view.findViewById<EditText>(R.id.defaultMinutes)
        val defaultSeconds = view.findViewById<EditText>(R.id.defaultSeconds)
        val voiceControl = view.findViewById<CheckBox>(R.id.voiceControl)
        val raiseVolume = view.findViewById<CheckBox>(R.id.raiseVolume)
        val startVolume = view.findViewById<EditText>(R.id.startVolume)
        val finalBeeps = view.findViewById<CheckBox>(R.id.finalBeeps)

        defaultMinutes.setText(prefs.getInt("default_minutes", 1).toString())
        defaultSeconds.setText(prefs.getInt("default_seconds", 0).toString())
        voiceControl.isChecked = prefs.getBoolean("voice_control", false)
        raiseVolume.isChecked = prefs.getBoolean("raise_volume", true)
        startVolume.setText(prefs.getInt("start_volume_percent", 60).toString())
        finalBeeps.isChecked = prefs.getBoolean("final_beeps", true)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putInt("default_minutes", defaultMinutes.text.toString().toIntOrNull() ?: 1)
                    .putInt("default_seconds", defaultSeconds.text.toString().toIntOrNull() ?: 0)
                    .putBoolean("voice_control", voiceControl.isChecked)
                    .putBoolean("raise_volume", raiseVolume.isChecked)
                    .putInt("start_volume_percent", startVolume.text.toString().toIntOrNull() ?: 60)
                    .putBoolean("final_beeps", finalBeeps.isChecked)
                    .apply()

                if (!timerRunning && !paused) {
                    loadDefaults()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        stopMusic()
        restorePreviousMediaVolume()
        try {
            toneGenerator.release()
        } catch (_: Exception) {
        }
    }
}