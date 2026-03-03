package com.example.talkingtimer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent { MaterialTheme { MusicBeepVoiceTimerScreen(this) } }
    }
}

@Composable
fun MusicBeepVoiceTimerScreen(context: Context) {
    val prefs = remember { context.getSharedPreferences("timer_prefs", Context.MODE_PRIVATE) }

    // ---------- TIMER ----------
    var minutes by remember { mutableStateOf("1") }
    var seconds by remember { mutableStateOf("0") }
    var totalSeconds by remember { mutableStateOf(60) }
    var remainingSeconds by remember { mutableStateOf(60) }
    var running by remember { mutableStateOf(false) }

    fun clampInt(text: String): Int = (text.toIntOrNull() ?: 0).coerceAtLeast(0)

    fun resetFromInputs() {
        val m = clampInt(minutes)
        val s = clampInt(seconds).coerceIn(0, 59)
        minutes = m.toString()
        seconds = s.toString()
        totalSeconds = m * 60 + s
        remainingSeconds = totalSeconds
    }

    // ---------- SETTINGS ----------
    var musicVolume by remember { mutableStateOf(prefs.getFloat("music_volume", 0.8f).coerceIn(0f, 1f)) }
    var beepVolume by remember { mutableStateOf(prefs.getFloat("beep_volume", 1.0f).coerceIn(0f, 1f)) }
    var keepMusicAfterEnd by remember { mutableStateOf(prefs.getBoolean("keep_music_after_end", false)) }
    var voiceControlOn by remember { mutableStateOf(prefs.getBoolean("voice_on", true)) }

    // Custom beep
    var beepCount by remember { mutableStateOf(prefs.getInt("beep_count", 1).coerceIn(1, 5)) }
    var beepSoundUri by remember { mutableStateOf(prefs.getString("beep_uri", null)?.let { Uri.parse(it) }) }
    var last10Enabled by remember { mutableStateOf(prefs.getBoolean("last10_enabled", true)) }

    // ---------- PLAYLIST ----------
    var playlist by remember { mutableStateOf(loadUriList(prefs.getString("playlist_uris", null))) }
    var currentIndex by remember { mutableStateOf(prefs.getInt("playlist_index", 0).coerceAtLeast(0)) }

    fun currentTrackUri(): Uri? {
        if (playlist.isEmpty()) return null
        if (currentIndex !in playlist.indices) currentIndex = 0
        return playlist[currentIndex]
    }

    fun nextTrack() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
    }

    fun prevTrack() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
    }

    // ---------- MEDIA ----------
    val musicPlayer = remember { MediaPlayer() }

    fun setMusicVolumeSafe(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        try { musicPlayer.setVolume(vol, vol) } catch (_: Exception) {}
    }

    fun stopMusicImmediate() {
        try {
            if (musicPlayer.isPlaying) musicPlayer.stop()
            musicPlayer.reset()
        } catch (_: Exception) {}
    }

    suspend fun fadeMusicTo(target: Float, durationMs: Int) {
        val start = musicVolume.coerceIn(0f, 1f) // baseline; good enough
        val t = target.coerceIn(0f, 1f)
        val steps = max(1, durationMs / 30)
        for (i in 1..steps) {
            val v = start + (t - start) * (i.toFloat() / steps.toFloat())
            setMusicVolumeSafe(v)
            delay(30)
        }
        setMusicVolumeSafe(t)
    }

    suspend fun stopMusicWithFadeOut() {
        val playing = try { musicPlayer.isPlaying } catch (_: Exception) { false }
        if (playing) fadeMusicTo(0f, 600)
        stopMusicImmediate()
    }

    suspend fun startCurrentTrackWithFadeIn() {
        val uri = currentTrackUri() ?: return
        try {
            musicPlayer.reset()
            musicPlayer.setDataSource(context, uri)
            musicPlayer.isLooping = false
            musicPlayer.prepare()
            setMusicVolumeSafe(0f)
            musicPlayer.start()
            fadeMusicTo(musicVolume, 800)
        } catch (_: Exception) {
            stopMusicImmediate()
        }
    }

    // ---------- REQUEST FLAGS (declared BEFORE use) ----------
    var playRequested by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }

    // Auto-advance on song end
    DisposableEffect(playlist, currentIndex, running, keepMusicAfterEnd) {
        musicPlayer.setOnCompletionListener {
            val shouldContinue = running || keepMusicAfterEnd
            if (shouldContinue && playlist.isNotEmpty()) {
                nextTrack()
                playRequested = true
            }
        }
        onDispose { musicPlayer.setOnCompletionListener(null) }
    }

    // Apply volume live
    LaunchedEffect(musicVolume) {
        val playing = try { musicPlayer.isPlaying } catch (_: Exception) { false }
        if (playing) setMusicVolumeSafe(musicVolume)
    }

    // Handle queued play (Next/Prev/Auto-advance)
    LaunchedEffect(playRequested) {
        if (playRequested) {
            stopMusicImmediate()
            startCurrentTrackWithFadeIn()
            playRequested = false
        }
    }

    // Handle queued stop (Pause/Reset)
    LaunchedEffect(stopRequested) {
        if (stopRequested) {
            stopMusicWithFadeOut()
            stopRequested = false
        }
    }

    // ---------- BEEP (fallback tone + custom beep audio) ----------
    val toneGen = remember(beepVolume) {
        ToneGenerator(AudioManager.STREAM_MUSIC, (beepVolume * 100).toInt().coerceIn(0, 100))
    }
    DisposableEffect(beepVolume) {
        onDispose { try { toneGen.release() } catch (_: Exception) {} }
    }

    // Play custom beep N times (or fallback tone if none selected)
    var customBeepRequest by remember { mutableStateOf(0) }

    fun requestBeep(times: Int) {
        customBeepRequest = times.coerceIn(1, 10)
    }

    // Duck music while beeping
    fun duckMusicForBeep(): Float {
        val playing = try { musicPlayer.isPlaying } catch (_: Exception) { false }
        if (!playing) return -1f
        // we don't have a getter for the true current volume, so use slider as baseline
        val baseline = musicVolume.coerceIn(0f, 1f)
        val ducked = (baseline * 0.25f).coerceIn(0f, 1f)
        setMusicVolumeSafe(ducked)
        return baseline
    }

    fun restoreMusicAfterBeep(baseline: Float) {
        val playing = try { musicPlayer.isPlaying } catch (_: Exception) { false }
        if (playing && baseline >= 0f) setMusicVolumeSafe(baseline.coerceIn(0f, 1f))
    }

    // Sequentially play beep sound N times with small gaps
    LaunchedEffect(customBeepRequest, beepSoundUri, beepVolume, musicVolume) {
        val times = customBeepRequest
        if (times <= 0) return@LaunchedEffect

        val baseline = duckMusicForBeep()

        for (i in 1..times) {
            val uri = beepSoundUri
            if (uri == null) {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
            } else {
                try {
                    val p = MediaPlayer()
                    p.setAudioStreamType(AudioManager.STREAM_MUSIC)
                    p.setDataSource(context, uri)
                    p.setOnCompletionListener { mp -> mp.release() }
                    p.prepare()
                    // approximate volume: MediaPlayer volume is per-player; we apply beepVolume here
                    val v = beepVolume.coerceIn(0f, 1f)
                    p.setVolume(v, v)
                    p.start()
                } catch (_: Exception) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
                }
            }
            delay(220) // spacing between beeps
        }

        delay(120)
        restoreMusicAfterBeep(baseline)

        customBeepRequest = 0
    }

    // ---------- PICKERS ----------
    val pickMultipleMusicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (u in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            playlist = uris
            currentIndex = 0
        }
    }

    val pickBeepSoundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            beepSoundUri = uri
        }
    }

    // ---------- VOICE CONTROL ----------
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    var lastHeard by remember { mutableStateOf("") }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(Unit) {
        onDispose { try { speechRecognizer?.destroy() } catch (_: Exception) {} }
    }

    fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }

    fun handleCommand(text: String) {
        val t = text.lowercase()
        when {
            t.contains("start") -> {
                resetFromInputs()
                if (totalSeconds > 0) {
                    running = false
                    running = true
                }
            }
            t.contains("pause") || t.contains("stop") -> {
                running = false
                stopRequested = true
            }
            t.contains("reset") -> {
                running = false
                resetFromInputs()
                stopRequested = true
            }
            t.contains("next") -> {
                nextTrack()
                if (running || keepMusicAfterEnd) playRequested = true
            }
            t.contains("previous") || t.contains("prev") -> {
                prevTrack()
                if (running || keepMusicAfterEnd) playRequested = true
            }
        }
    }

    fun startListening() {
        val sr = speechRecognizer ?: return
        try {
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (voiceControlOn && micGranted) {
                        try { sr.startListening(buildRecognizerIntent()) } catch (_: Exception) {}
                    }
                }

                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = texts?.firstOrNull().orEmpty()
                    if (best.isNotBlank()) {
                        lastHeard = best
                        handleCommand(best)
                    }
                    if (voiceControlOn && micGranted) {
                        try { sr.startListening(buildRecognizerIntent()) } catch (_: Exception) {}
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = texts?.firstOrNull().orEmpty()
                    if (best.isNotBlank()) lastHeard = best
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(buildRecognizerIntent())
        } catch (_: Exception) {}
    }

    fun stopListening() {
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
    }

    LaunchedEffect(voiceControlOn, micGranted) {
        if (voiceControlOn && micGranted) startListening() else stopListening()
    }

    // ---------- TIMER LOOP ----------
    LaunchedEffect(running) {
        if (running) {
            if (playlist.isNotEmpty()) startCurrentTrackWithFadeIn()

            var lastBeepAt = -1
            val beepEverySeconds = 30
            val lastSecondsCountdown = 10

            while (running && remainingSeconds > 0) {
                delay(1000)
                remainingSeconds -= 1

                val shouldBeep30 =
                    (remainingSeconds % beepEverySeconds == 0 && remainingSeconds != totalSeconds)

                val shouldBeepLast10 =
                    last10Enabled && (remainingSeconds in 1..lastSecondsCountdown)

                val shouldBeep = shouldBeep30 || shouldBeepLast10

                if (shouldBeep && remainingSeconds != lastBeepAt) {
                    lastBeepAt = remainingSeconds
                    requestBeep(beepCount)
                }

                if (remainingSeconds == 0) {
                    requestBeep(beepCount)
                    running = false
                }
            }
        } else {
            if (!keepMusicAfterEnd) stopMusicWithFadeOut()
        }
    }

    // ---------- PERSIST ----------
    LaunchedEffect(
        musicVolume, beepVolume, keepMusicAfterEnd, voiceControlOn,
        beepCount, beepSoundUri, last10Enabled,
        playlist, currentIndex
    ) {
        prefs.edit()
            .putFloat("music_volume", musicVolume)
            .putFloat("beep_volume", beepVolume)
            .putBoolean("keep_music_after_end", keepMusicAfterEnd)
            .putBoolean("voice_on", voiceControlOn)
            .putInt("beep_count", beepCount)
            .putString("beep_uri", beepSoundUri?.toString())
            .putBoolean("last10_enabled", last10Enabled)
            .putString("playlist_uris", saveUriList(playlist))
            .putInt("playlist_index", currentIndex)
            .apply()
    }

    // ---------- UI ----------
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
            onClick = { pickMultipleMusicLauncher.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (playlist.isEmpty()) "Select Music (Multiple)" else "Change Playlist (${playlist.size})")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { prevTrack(); if (running || keepMusicAfterEnd) playRequested = true },
                modifier = Modifier.weight(1f),
                enabled = playlist.isNotEmpty()
            ) { Text("Prev") }

            OutlinedButton(
                onClick = { nextTrack(); if (running || keepMusicAfterEnd) playRequested = true },
                modifier = Modifier.weight(1f),
                enabled = playlist.isNotEmpty()
            ) { Text("Next") }
        }

        Divider()

        Button(
            onClick = { pickBeepSoundLauncher.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (beepSoundUri == null) "Select Beep Sound" else "Change Beep Sound")
        }

        Text("Beep count (how many beeps each time): $beepCount")
        Slider(
            value = beepCount.toFloat(),
            onValueChange = { beepCount = it.toInt().coerceIn(1, 5) },
            valueRange = 1f..5f,
            steps = 3
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = last10Enabled, onCheckedChange = { last10Enabled = it })
            Spacer(Modifier.width(8.dp))
            Text("Enable last 10-second beeps")
        }

        Divider()

        Text("Music volume: ${(musicVolume * 100).toInt()}%")
        Slider(value = musicVolume, onValueChange = { musicVolume = it.coerceIn(0f, 1f) })

        Text("Beep volume: ${(beepVolume * 100).toInt()}%")
        Slider(value = beepVolume, onValueChange = { beepVolume = it.coerceIn(0f, 1f) })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = keepMusicAfterEnd, onCheckedChange = { keepMusicAfterEnd = it })
            Spacer(Modifier.width(8.dp))
            Text("Keep music playing after timer ends")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = voiceControlOn, onCheckedChange = { voiceControlOn = it })
            Spacer(Modifier.width(8.dp))
            Text("Voice control: say “start / pause / reset / next / previous”")
        }

        if (voiceControlOn && !micGranted) {
            Button(
                onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable Microphone Permission") }
        } else if (voiceControlOn) {
            Text("Heard: $lastHeard", style = MaterialTheme.typography.bodyMedium)
        }

        Divider()

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
            onClick = { running = false; stopRequested = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = running
        ) { Text("Pause") }

        OutlinedButton(
            onClick = { running = false; resetFromInputs(); stopRequested = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset") }
    }
}

// --- helpers for saving URI lists ---
private fun saveUriList(list: List<Uri>): String = list.joinToString("|") { it.toString() }

private fun loadUriList(raw: String?): List<Uri> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split("|").mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
}