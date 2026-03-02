package com.example.talkingtimer

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) {
            tts?.language = Locale.US
        }

        setContent {
            MaterialTheme {
                TimerScreen { text ->
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }

    override fun onDestroy() {
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

    LaunchedEffect(running) {
        while (running && timeLeft > 0) {
            delay(1000)
            timeLeft--
            speak("$timeLeft seconds remaining")
            if (timeLeft == 0) {
                speak("Time is up")
                running = false
            }
        }
    }

    Column(Modifier.padding(20.dp)) {

        Text("Talking Timer", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = minutes,
            onValueChange = { minutes = it.filter(Char::isDigit) },
            label = { Text("Minutes") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        OutlinedTextField(
            value = seconds,
            onValueChange = { seconds = it.filter(Char::isDigit) },
            label = { Text("Seconds") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(Modifier.height(20.dp))

        Button(onClick = {
            val m = minutes.toIntOrNull() ?: 0
            val s = seconds.toIntOrNull() ?: 0
            timeLeft = m * 60 + s
            if (timeLeft > 0) running = true
        }) {
            Text("Start")
        }
    }
}
