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

        setContent {
            var ttsReady by remember { mutableStateOf(false) }
            var ttsStatus by remember { mutableStateOf("Initializing…") }

            DisposableEffect(Unit) {
                tts = TextToSpeech(this@MainActivity) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val langResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
                        tts?.setSpeechRate(1.0f)
                        ttsReady = (langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED)
                        ttsStatus = if (ttsReady) "Ready" else "Language missing/not supported"
                    } else {
                        ttsReady = false
                        ttsStatus = "Init failed (status=$status)"
                    }
                }

                onDispose {
                    tts?.stop()
                    tts?.shutdown()
                    tts = null
                }
            }

            MaterialTheme {
                TimerScreen(
                    ttsReady = ttsReady,
                    ttsStatus = ttsStatus,
                    speak = { text ->
                        if (ttsReady) {
                            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "timer")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun TimerScreen(
    ttsReady: Boolean,
    ttsStatus: String,
    speak: (String) -> Unit
) {
    var minutes by remember { mutableStateOf("1") }
    var seconds by remember { mutableStateOf("0") }

    var timeLeft by remember { mutableStateOf(60) }
    var running by remember { mutableStateOf(false) }

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
        Text("TTS: $ttsStatus", color = if (ttsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
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