package com.example.talkingtimer

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        setContent {
            MaterialTheme {
                TimerScreen(
                    speak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "timer") }
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun TimerScreen(speak: (String) -> Unit) {
    var minutes by remember { mutableStateOf("1") }
    var seconds by remember { mutableStateOf("0") }

    var timeLeft by remember { mutableStateOf(60) }
    var running by remember { mutableStateOf(false) }

    // This is just to prove clicks work
    var clickCount by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Ready") }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running && timeLeft > 0) {
            delay(1000)
            timeLeft -= 1
            if (timeLeft == 0) {
                status = "Done"
                speak("Time is up")
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
        Text("Talking Timer", style = MaterialTheme.typography.headlineSmall)
        Text("Status: $status | Clicks: $clickCount")
        Text("Remaining: $timeLeft seconds", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
            label = { Text("Minutes") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = seconds,
            onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
            label = { Text("Seconds") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                clickCount += 1
                val m = minutes.toIntOrNull() ?: 0
                val s = seconds.toIntOrNull() ?: 0
                val total = m * 60 + s

                if (total <= 0) {
                    status = "Enter a time"
                    speak("Please enter a time")
                    return@Button
                }

                timeLeft = total
                status = "Running"
                speak("Timer started")

                // Force restart even if already true
                running = false
                running = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start")
        }

        OutlinedButton(
            onClick = {
                clickCount += 1
                running = false
                status = "Paused"
                speak("Paused")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Pause") }

        OutlinedButton(
            onClick = {
                clickCount += 1
                running = false
                status = "Reset"
                val m = minutes.toIntOrNull() ?: 0
                val s = seconds.toIntOrNull() ?: 0
                timeLeft = (m * 60 + s).coerceAtLeast(0)
                speak("Reset")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset") }
    }
}