package com.example.talkingtimer

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MusicBeepTimerScreen(this) } }
    }
}

@Composable
fun MusicBeepTimerScreen(context: Context) {
    val prefs = remember { context.getSharedPreferences("timer_prefs", Context.MODE_PRIVATE) }

    // Timer inputs/state
    var minutes by remember { mutableStateOf("1") }
    var seconds by remember { mutableStateOf("0") }
    var totalSeconds by remember { mutableStateOf(60) }
    var remainingSeconds by remember { mutableStateOf(60) }
    var running by remember { mutableStateOf(false) }

    // Settings (persisted)
    var musicVolume by remember { mutableStateOf(prefs.getFloat("music_volume", 0.8f).coerceIn(0f, 1f)) }
    var beepVolume by remember { mutableStateOf(prefs.getFloat("beep_volume", 1.0f).coerceIn(0f, 1f)) }
    var keepMusicAfterEnd by remember { mutableStateOf(prefs.getBoolean("keep_music_after_end", false)) }

    // Remember last picked song (persisted)
    var selectedMusicUri by remember {
        mutableStateOf(prefs.getString("music_uri", null)?.let { Uri.parse(it) })
    }

    // Media player
    val mediaPlayer = remember { MediaPlayer() }

    // Tone generator (recreated when beep volume changes)
    val toneGen = remember(beepVolume) {
        ToneGenerator(AudioManager.STREAM_MUSIC, (beepVolume * 100).toInt().coerceIn(0, 100))
    }

    // Control: stop music when user presses Pause/Reset
    var stopRequested by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            try { mediaPlayer.release() } catch (_: Exception) {}
        }
    }
    DisposableEffect(beepVolume) {
        onDispose { try { toneGen.release() } catch (_: Exception) {} }
    }

    // Picker that supports persistable access
    val musicPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers don’t allow persistable permission; still try using the URI.
            }

            selectedMusicUri = uri
            prefs.edit().putString("music_uri", uri.toString()).apply()
        }
    }

    fun saveSettings() {
        prefs.edit()
            .putFloat("music_volume", musicVolume)
            .putFloat("beep_volume", beepVolume)
            .putBoolean("keep_music_after_end", keepMusicAfterEnd)
            .apply()
    }

    fun clampInt(text: String): Int = (text.toIntOrNull() ?: 0).coerceAtLeast(0)

    fun resetFromInputs() {
        val m = clampInt(minutes)
        val s = clampInt(seconds).coerceIn(0, 59)
        minutes = m.toString()
        seconds = s.toString()
        totalSeconds = m * 60 + s
        remainingSeconds = totalSeconds
    }

    fun setPlayerVolumeSafe(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        try { mediaPlayer.setVolume(vol, vol) } catch (_: Exception) {}
    }

    suspend fun fadeMusicTo(target: Float, durationMs: Int) {
        val start = musicVolume.coerceIn(0f, 1f) // baseline; good enough for this app
        val t = target.coerceIn(0f, 1f)
        val steps = max(1, durationMs / 30)
        for (i in 1..steps) {
            val v = start + (t - start) * (i.toFloat() / steps.toFloat())
            setPlayerVolumeSafe(v)
            delay(30)
        }
        setPlayerVolumeSafe(t)
    }

    fun stopMusicImmediate() {
        try {
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.reset()
        } catch (_: Exception) {}
    }

    suspend fun stopMusicWithFadeOut() {
        val playing = try { mediaPlayer.isPlaying } catch (_: Exception) { false }
        if (playing) fadeMusicTo(0f, 700)
        stopMusicImmediate()
    }

    suspend fun startMusicWithFadeIn() {
        val uri = selectedMusicUri ?: return
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.isLooping = true
            mediaPlayer.prepare()
            setPlayerVolumeSafe(0f)
            mediaPlayer.start()
            fadeMusicTo(musicVolume, 900)
        } catch (_: Exception) {
            stopMusicImmediate()
        }
    }

    // Save settings + apply live music volume
    LaunchedEffect(musicVolume, beepVolume, keepMusicAfterEnd) {
        saveSettings()
        val playing = try { mediaPlayer.isPlaying } catch (_: Exception) { false }
        if (playing) setPlayerVolumeSafe(musicVolume)
    }

    // Duck music briefly during beep
    fun beepWithDuck() {
        val playing = try { mediaPlayer.isPlaying } catch (_: Exception) { false }
        if (playing) setPlayerVolumeSafe((musicVolume * 0.25f).coerceIn(0f, 1f))
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
    }

    // Restore after duck
    var restoreDuck by remember { mutableStateOf(false) }
    LaunchedEffect(restoreDuck, musicVolume) {
        if (restoreDuck) {
            delay(180)
            val playing = try { mediaPlayer.isPlaying } catch (_: Exception) { false }
            if (playing) setPlayerVolumeSafe(musicVolume)
            restoreDuck = false
        }
    }

    // Pause/Reset should stop music even if keepMusicAfterEnd = true
    LaunchedEffect(stopRequested) {
        if (stopRequested) {
            stopMusicWithFadeOut()
            stopRequested = false
        }
    }

    // Timer loop
    LaunchedEffect(running) {
        if (running) {
            startMusicWithFadeIn()

            var lastBeepAt = -1
            val beepEverySeconds = 30
            val lastSecondsCountdown = 10

            while (running && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds -= 1

                val shouldBeep =
                    (remainingSeconds % beepEverySeconds == 0 && remainingSeconds != totalSeconds) ||
                            (remainingSeconds in 1..lastSecondsCountdown)

                if (shouldBeep && remainingSeconds != lastBeepAt) {
                    lastBeepAt = remainingSeconds
                    beepWithDuck()
                    restoreDuck = true
                }

                if (remainingSeconds == 0) {
                    beepWithDuck()
                    restoreDuck = true
                    running = false
                }
            }
        } else {
            if (!keepMusicAfterEnd) {
                stopMusicWithFadeOut()
            }
        }
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Music Timer", style = MaterialTheme.typography.headlineSmall)
        Text("Remaining: ${remainingSeconds}s", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
            label = { Text("Minutes") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running
        )

        OutlinedTextField(
            value = seconds,
            onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
            label = { Text("Seconds") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running
        )

        Button(
            onClick = { musicPickerLauncher.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (selectedMusicUri == null) "Select Music" else "Change Music")
        }

        Text("Music volume: ${(musicVolume * 100).toInt()}%")
        Slider(
            value = musicVolume,
            onValueChange = { musicVolume = it.coerceIn(0f, 1f) },
            valueRange = 0f..1f
        )

        Text("Beep volume: ${(beepVolume * 100).toInt()}%")
        Slider(
            value = beepVolume,
            onValueChange = { beepVolume = it.coerceIn(0f, 1f) },
            valueRange = 0f..1f
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = keepMusicAfterEnd, onCheckedChange = { keepMusicAfterEnd = it })
            Spacer(Modifier.width(8.dp))
            Text("Keep music playing after timer ends")
        }

        Button(
            onClick = {
                resetFromInputs()
                if (totalSeconds > 0) {
                    running = false
                    running = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running
        ) { Text("Start") }

        OutlinedButton(
            onClick = {
                running = false
                stopRequested = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = running
        ) { Text("Pause") }

        OutlinedButton(
            onClick = {
                running = false
                resetFromInputs()
                stopRequested = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset") }

        Divider()
        Text(
            "Beeps every 30 seconds and every second in the last 10 seconds.\nTip: raise Media volume.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}