package com.noisemachine.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noisemachine.app.playback.PlaybackState
import com.noisemachine.app.playback.PlaybackViewModel
import com.noisemachine.app.playback.TimerState
import kotlinx.coroutines.delay

// ── Theme colors ─────────────────────────────────────────────────
private val DarkNavy = Color(0xFF0F1A2E)
private val MutedBlue = Color(0xFF5A7BAF)
private val DimBlue = Color(0xFF2A3A5C)

private val NoiseMachineColors = darkColorScheme(
    background = DarkNavy,
    surface = DarkNavy,
    onBackground = MutedBlue,
    onSurface = MutedBlue,
    primary = MutedBlue,
    onPrimary = DarkNavy,
)

// ── Timer presets (cycle order) ──────────────────────────────────
private data class TimerPreset(val label: String, val durationMs: Long)

private val timerPresets = listOf(
    TimerPreset("∞", 0L),       // no timer
    TimerPreset("15m", 15 * 60 * 1000L),
    TimerPreset("30m", 30 * 60 * 1000L),
    TimerPreset("1h", 60 * 60 * 1000L),
    TimerPreset("2h", 2 * 60 * 60 * 1000L),
)

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
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onStopClicked()
    }

    MaterialTheme(colorScheme = NoiseMachineColors) {
        if (showSettings) {
            SettingsScreen(onBack = { showSettings = false })
        } else {
            MainScreen(
                state = state,
                color = color,
                timerState = timerState,
                onPlay = viewModel::onPlayClicked,
                onStop = viewModel::onStopClicked,
                onColorChanged = viewModel::onColorChanged,
                onTimerSelected = viewModel::onTimerSelected,
                onSettingsClicked = { showSettings = true },
            )
        }
    }
}

@Composable
private fun MainScreen(
    state: PlaybackState,
    color: Float,
    timerState: TimerState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onColorChanged: (Float) -> Unit,
    onTimerSelected: (Long) -> Unit,
    onSettingsClicked: () -> Unit,
) {
    // Timer cycling state
    var timerIndex by rememberSaveable { mutableIntStateOf(0) }
    var showTimerLabel by remember { mutableStateOf(false) }
    var timerLabelKey by remember { mutableIntStateOf(0) }

    // Auto-hide timer label after 2s
    LaunchedEffect(timerLabelKey) {
        if (showTimerLabel) {
            delay(2000)
            showTimerLabel = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Top row: timer (left) + settings (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = {
                timerIndex = (timerIndex + 1) % timerPresets.size
                val preset = timerPresets[timerIndex]
                if (preset.durationMs > 0) {
                    onTimerSelected(preset.durationMs)
                }
                // Show label briefly
                showTimerLabel = true
                timerLabelKey++
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_timer),
                    contentDescription = "Timer",
                    tint = MutedBlue,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onSettingsClicked) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MutedBlue,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Center: play/stop button OR timer selection label
        Box(modifier = Modifier.align(Alignment.Center)) {
            // Timer selection label (large, centered, fades out)
            AnimatedVisibility(
                visible = showTimerLabel,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = timerPresets[timerIndex].label,
                    color = MutedBlue,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            // Play/Stop button (hidden while timer label is showing)
            AnimatedVisibility(
                visible = !showTimerLabel,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val isPlaying = state == PlaybackState.Playing ||
                    state == PlaybackState.FadingIn ||
                    state == PlaybackState.FadingOut

                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onStop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(MutedBlue, RoundedCornerShape(8.dp)),
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = MutedBlue,
                        modifier = Modifier
                            .size(120.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPlay() },
                    )
                }
            }
        }

        // Color slider — positioned 1/3 up from bottom (verticalBias = 0.33)
        Slider(
            value = color,
            onValueChange = onColorChanged,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = MutedBlue,
                activeTrackColor = MutedBlue,
                inactiveTrackColor = DimBlue,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .align(BiasAlignment(0f, 0.33f))
                .padding(horizontal = 32.dp),
        )

        // Timer countdown (subtle, below timer icon when armed)
        if (timerState is TimerState.Armed && !showTimerLabel) {
            val remaining = timerState.remainingMs
            val minutes = remaining / 60_000
            val seconds = (remaining % 60_000) / 1000
            Text(
                text = "%d:%02d".format(minutes, seconds),
                color = MutedBlue.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 96.dp, start = 32.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = DarkNavy,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = MutedBlue) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MutedBlue,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkNavy),
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Fade Durations", style = MaterialTheme.typography.titleMedium, color = MutedBlue)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Fade in", color = MutedBlue)
                Text("${PlaybackViewModel.DEFAULT_FADE_IN_MS / 1000}s", color = MutedBlue)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Fade out", color = MutedBlue)
                Text("${PlaybackViewModel.DEFAULT_FADE_OUT_MS / 1000}s", color = MutedBlue)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenIdlePreview() {
    MaterialTheme(colorScheme = NoiseMachineColors) {
        MainScreen(
            state = PlaybackState.Idle,
            color = 0.2f,
            timerState = TimerState.Off,
            onPlay = {},
            onStop = {},
            onColorChanged = {},
            onTimerSelected = {},
            onSettingsClicked = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPlayingPreview() {
    MaterialTheme(colorScheme = NoiseMachineColors) {
        MainScreen(
            state = PlaybackState.Playing,
            color = 0.5f,
            timerState = TimerState.Armed(1_740_000),
            onPlay = {},
            onStop = {},
            onColorChanged = {},
            onTimerSelected = {},
            onSettingsClicked = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme(colorScheme = NoiseMachineColors) {
        SettingsScreen(onBack = {})
    }
}
