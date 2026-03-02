package com.example.talkingtimer

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

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

    // Settings
    var musicVolume by remember { mutableStateOf(prefs.getFloat("music_volume", 0.8f).coerceIn(0f, 1f)) }
    var beepVolume by remember { mutableStateOf(prefs.getFloat("beep_volume", 1.0f).coerceIn(0f, 1f)) }
    var keepMusicAfterEnd by remember { mutableStateOf(prefs.getBoolean("keep_music_after_end", false)) }

    // Remember last picked song URI (persistable)
    var selectedMusicUri by remember {
        mutableStateOf(prefs.getString("music_uri", null)?.let { Uri.parse(it) })
    }

    // Media player (single instance)
    val mediaPlayer = remember { MediaPlayer() }
    val isMusicPrepared = remember { AtomicBoolean(false) }

    // Tone generator (recreate if beepVolume changes)
    val toneGen = remember(beepVolume) {
        // Volume is 0..100 here; map slider 0..1 -> 0..100
        ToneGenerator(AudioManager.STREAM_MUSIC, (beepVolume * 100).toInt().coerceIn(0, 100))
    }
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
                // Persist permission so it still works after app restarts
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers may not allow persist; still store URI and try.
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

    suspend fun fadeMusicTo(target: Float, durationMs: Int) {
        val start = currentMusicVolume(mediaPlayer, musicVolume)
        val t = target.coerceIn(0f, 1f)
        val steps = max(1, durationMs / 30)
        for (i in 1..steps) {
            if (!isActive) return
            val v = start + (t - start) * (i.toFloat() / steps.toFloat())
            setPlayerVolumeSafe(mediaPlayer, v)
            delay(30)
        }
        setPlayerVolumeSafe(mediaPlayer, t)
    }

    fun beepDuckMusic() {
        // Duck music briefly so the beep stands out
        if (mediaPlayer.isPlaying) {
            val original = musicVolume
            val ducked = (original * 0.25f).coerceIn(0f, 1f)
            setPlayerVolumeSafe(mediaPlayer, ducked)
            // restore after short delay (fire-and-forget via LaunchedEffect helper below)
        }
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
    }

    // Helper flag to restore volume after beep duck
    var restoreMusicAfterDuck by remember { mutableStateOf(false) }
    LaunchedEffect(restoreMusicAfterDuck) {
        if (restoreMusicAfterDuck) {
            delay(180)
            if (mediaPlayer.isPlaying) setPlayerVolumeSafe(mediaPlayer, musicVolume)
            restoreMusicAfterDuck = false
        }
    }

    fun stopMusicImmediate() {
        try {
            if (mediaPlayer.isPlaying) mediaPlayer.stop()
            mediaPlayer.reset()
            isMusicPrepared.set(false)
        } catch (_: Exception) {}
    }

    suspend fun stopMusicWithFadeOut() {
        if (mediaPlayer.isPlaying) {
            fadeMusicTo(0f, 800)
            stopMusicImmediate()
        } else {
            stopMusicImmediate()
        }
    }

    suspend fun startMusicWithFadeIn() {
        val uri = selectedMusicUri ?: return
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.isLooping = true
            mediaPlayer.prepare()
            isMusicPrepared.set(true)
            setPlayerVolumeSafe(mediaPlayer, 0f)
            mediaPlayer.start()
            fadeMusicTo(musicVolume, 1200)
        } catch (_: Exception) {
            stopMusicImmediate()
        }
    }

    // Persist settings whenever sliders/toggles change
    LaunchedEffect(musicVolume, beepVolume, keepMusicAfterEnd) {
        saveSettings()
        // If music is playing, apply volume change immediately
        if (mediaPlayer.isPlaying) setPlayerVolumeSafe(mediaPlayer, musicVolume)
    }

    // Timer loop
    LaunchedEffect(running) {
        if (running) {
            // Start music (if any)
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
                    beepDuckMusic()
                    restoreMusicAfterDuck = true
                }

                if (remainingSeconds == 0) {
                    // final beep
                    beepDuckMusic()
                    restoreMusicAfterDuck = true

                    running = false
                }
            }
        } else {
            // Timer stopped/paused/ended
            if (!keepMusicAfterEnd) {
                stopMusicWithFadeOut()
            } else {
                // If timer ended naturally, keep music playing.
                // If user pressed Pause/Reset, they probably want it stopped — handled below.
            }
        }
    }

    // Stop music on Pause/Reset actions even if keepMusicAfterEnd is true
    suspend fun userStopMusic() {
        stopMusicWithFadeOut()
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

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(
                checked = keepMusicAfterEnd,
                onCheckedChange = { keepMusicAfterEnd = it }
            )
            Spacer(Modifier.width(8.dp))
            Text("Keep music playing after timer ends")
        }

        Button(
            onClick = {
                resetFromInputs()
                if (totalSeconds > 0) {
                    // Force restart even if already running
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
                // User intent: stop music
                // (launch coroutine via LaunchedEffect helper)
                // We'll do it by toggling a state:
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
        Text("Beeps every 30 seconds and every second in the last 10 seconds.\nTip: raise Media volume.", style = MaterialTheme.typography.bodyMedium)
    }

    // A small state-driven way to stop music with fade from button clicks
    var stopRequested by remember { mutableStateOf(false) }
    LaunchedEffect(stopRequested) {
        if (stopRequested) {
            userStopMusic()
            stopRequested = false
        }
    }
}

// Helpers
private fun setPlayerVolumeSafe(mp: MediaPlayer, v: Float) {
    val vol = v.coerceIn(0f, 1f)
    try { mp.setVolume(vol, vol) } catch (_: Exception) {}
}

// We don't have a real getter, so treat UI slider as the "current" and only fade from that.
// (Good enough for our use: we always set volume through our code.)
private fun currentMusicVolume(mp: MediaPlayer, fallback: Float): Float {
    return try {
        if (mp.isPlaying) fallback else fallback
    } catch (_: Exception) { fallback }
}