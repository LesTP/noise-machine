package com.noisemachine.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

    // Phase 1: no foreground service, so stop audio when the Activity stops
    // (home press, back press, task switch). Phase 4 replaces this with a
    // foreground-service lifecycle that keeps playback alive in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onStopClicked()
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    PlaybackControls(
                        state = state,
                        onPlay = viewModel::onPlayClicked,
                        onStop = viewModel::onStopClicked,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlaybackState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Noise Machine", style = MaterialTheme.typography.headlineMedium)
        when (state) {
            PlaybackState.Idle -> Button(onClick = onPlay) { Text("Play") }
            PlaybackState.Playing -> Button(onClick = onStop) { Text("Stop") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsIdlePreview() {
    MaterialTheme {
        PlaybackControls(state = PlaybackState.Idle, onPlay = {}, onStop = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaybackControlsPlayingPreview() {
    MaterialTheme {
        PlaybackControls(state = PlaybackState.Playing, onPlay = {}, onStop = {})
    }
}
