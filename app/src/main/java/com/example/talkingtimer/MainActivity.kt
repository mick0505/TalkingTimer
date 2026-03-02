package com.example.talkingtimer

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                BeepingTimerScreen()
            }
        }
    }
}

@Composable
fun BeepingTimerScreen() {
    var minutes by remember { mutableStateOf("1") }
    var seconds by remember { mutableStateOf("0") }

    var totalSeconds by remember { mutableStateOf(60) }
    var remainingSeconds by remember { mutableStateOf(60) }
    var running by remember { mutableStateOf(false) }

    // Beep settings
    val beepEverySeconds = 30
    val lastSecondsCountdown = 10 // beeps every second in last 10 seconds
    val beepDurationMs = 120

    // One ToneGenerator for the whole screen lifetime
    val toneGen = remember {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }
    DisposableEffect(Unit) {
        onDispose { toneGen.release() }
    }

    fun beep() {
        // You can swap TONE_PROP_BEEP for other tones if you prefer
        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, beepDurationMs)
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

    LaunchedEffect(Unit) { resetFromInputs() }

    LaunchedEffect(running) {
        var lastBeepAt = -1
        while (running && remainingSeconds > 0) {
            delay(1000)
            remainingSeconds -= 1

            val shouldBeep =
                // Beep every 30 seconds (e.g., 90, 60, 30)
                (remainingSeconds % beepEverySeconds == 0 && remainingSeconds != totalSeconds) ||
                // Beep each second in last 10 seconds
                (remainingSeconds in 1..lastSecondsCountdown)

            if (shouldBeep && remainingSeconds != lastBeepAt) {
                lastBeepAt = remainingSeconds
                beep()
            }

            if (remainingSeconds == 0) {
                // Final longer beep(s)
                beep()
                running = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Beeping Timer", style = MaterialTheme.typography.headlineSmall)
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
            onClick = { running = false },
            modifier = Modifier.fillMaxWidth(),
            enabled = running
        ) { Text("Pause") }

        OutlinedButton(
            onClick = {
                running = false
                resetFromInputs()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset") }

        Divider()

        Text(
            "Beeps every 30 seconds, and every second in the last 10 seconds.\n" +
                    "Tip: Make sure Media volume is up.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}