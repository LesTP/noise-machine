package com.noisemachine.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noisemachine.app.playback.PlaybackState
import com.noisemachine.app.playback.PlaybackViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoiseMachineApp()
        }
    }
}

@Composable
fun NoiseMachineApp(
    viewModel: PlaybackViewModel = viewModel(factory = PlaybackViewModel.Factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val color by viewModel.color.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onStopClicked()
    }

    MaterialTheme {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                PlaybackControls(
                    state = state,
                    color = color,
                    onPlay = viewModel::onPlayClicked,
                    onStop = viewModel::onStopClicked,
                    onColorChanged = viewModel::onColorChanged,
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlaybackState,
    color: Float,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onColorChanged: (Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
    ) {
        Text(text = "Noise Machine", style = MaterialTheme.typography.headlineMedium)

        when (state) {
            PlaybackState.Idle -> Button(onClick = onPlay) { Text("Play") }
            PlaybackState.FadingIn -> Button(onClick = onStop, enabled = true) { Text("Stop") }
            PlaybackState.Playing -> Button(onClick = onStop) { Text("Stop") }
            PlaybackState.FadingOut -> Button(onClick = {}, enabled = false) { Text("Stopping…") }
        }

        Text(
            text = "Color",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = color,
            onValueChange = onColorChanged,
            valueRange = 0f..1f,
        )
        Text(
            text = if (color < 0.2f) "Bright" else if (color > 0.8f) "Deep" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsIdlePreview() {
    MaterialTheme {
        PlaybackControls(
            state = PlaybackState.Idle,
            color = 0f,
            onPlay = {},
            onStop = {},
            onColorChanged = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsPlayingPreview() {
    MaterialTheme {
        PlaybackControls(
            state = PlaybackState.Playing,
            color = 0.5f,
            onPlay = {},
            onStop = {},
            onColorChanged = {},
        )
    }
}
