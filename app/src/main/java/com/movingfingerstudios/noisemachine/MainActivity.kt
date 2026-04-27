package com.movingfingerstudios.noisemachine

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.CornerPathEffect
import android.graphics.Paint as NativePaint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movingfingerstudios.noisemachine.playback.PlaybackState
import com.movingfingerstudios.noisemachine.playback.PlaybackViewModel
import com.movingfingerstudios.noisemachine.playback.TimerState
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

// ── Fade duration options (D-34) ─────────────────────────────────
private data class FadeOption(val label: String, val ms: Long)

private val fadeOptions = listOf(
    FadeOption("0s", 0L),
    FadeOption("1s", 1_000L),
    FadeOption("2s", 2_000L),
    FadeOption("5s", 5_000L),
    FadeOption("10s", 10_000L),
)

class MainActivity : ComponentActivity() {

    private var playbackService: PlaybackService? = null
    private var bound = false

    /** Compose-observable state so the UI recomposes once the service is bound. */
    private val serviceState = mutableStateOf<PlaybackService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as PlaybackService.LocalBinder).getService()
            playbackService = service
            serviceState.value = service
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceState.value = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Put the service in "started" state so startForeground() is legal later.
        startService(Intent(this, PlaybackService::class.java))
        bindService(
            Intent(this, PlaybackService::class.java),
            connection,
            BIND_AUTO_CREATE,
        )

        setContent {
            val service by serviceState
            if (service != null) {
                NoiseMachineApp(service = service!!)
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}

@Composable
fun NoiseMachineApp(
    service: PlaybackService,
    viewModel: PlaybackViewModel = viewModel(
        factory = PlaybackViewModel.Factory(
            controller = service,
            appContext = LocalContext.current.applicationContext,
            timerController = service,
        ),
    ),
) {
    // Wire external stop requests (notification, focus loss) through the VM.
    DisposableEffect(service, viewModel) {
        service.onStopRequested = { viewModel.onStopClicked() }
        onDispose { service.onStopRequested = null }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val color by viewModel.color.collectAsStateWithLifecycle()
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()
    val texture by viewModel.texture.collectAsStateWithLifecycle()
    val stereoWidth by viewModel.stereoWidth.collectAsStateWithLifecycle()
    val microDriftDepth by viewModel.microDriftDepth.collectAsStateWithLifecycle()
    val fadeInMs by viewModel.fadeInMsFlow.collectAsStateWithLifecycle()
    val fadeOutMs by viewModel.fadeOutMsFlow.collectAsStateWithLifecycle()

    // POST_NOTIFICATIONS permission (API 33+, D-35)
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — playback works either way */ }

    var showSettings by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(colorScheme = NoiseMachineColors) {
        if (showSettings) {
            SettingsScreen(
                texture = texture,
                onTextureChanged = viewModel::onTextureChanged,
                stereoWidth = stereoWidth,
                onStereoWidthChanged = viewModel::onStereoWidthChanged,
                microDriftDepth = microDriftDepth,
                onMicroDriftDepthChanged = viewModel::onMicroDriftDepthChanged,
                fadeInMs = fadeInMs,
                onFadeInChanged = viewModel::onFadeInChanged,
                fadeOutMs = fadeOutMs,
                onFadeOutChanged = viewModel::onFadeOutChanged,
                onBack = { showSettings = false },
            )
        } else {
            MainScreen(
                state = state,
                color = color,
                timerState = timerState,
                initialTimerIndex = timerPresets.indexOfFirst {
                    it.durationMs == viewModel.lastTimerDurationMs
                }.coerceAtLeast(0),
                onPlay = {
                    // Request POST_NOTIFICATIONS on first play (API 33+)
                    if (Build.VERSION.SDK_INT >= 33) {
                        val perm = Manifest.permission.POST_NOTIFICATIONS
                        if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(perm)
                        }
                    }
                    viewModel.onPlayClicked()
                },
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
    initialTimerIndex: Int,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onColorChanged: (Float) -> Unit,
    onTimerSelected: (Long) -> Unit,
    onSettingsClicked: () -> Unit,
) {
    // Timer cycling state — restore from ViewModel's persisted duration.
    var timerIndex by rememberSaveable { mutableIntStateOf(initialTimerIndex) }
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
                onTimerSelected(preset.durationMs)
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

        // Timer selection label (large, centered, above play button, fades out)
        AnimatedVisibility(
            visible = showTimerLabel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(BiasAlignment(0f, -0.55f)),
        ) {
            Text(
                text = timerPresets[timerIndex].label,
                color = MutedBlue,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
            )
        }

        // Play/Stop button — 1/3 from top, crossfade matches audio fade duration
        val showStop = state == PlaybackState.Playing || state == PlaybackState.FadingIn
        AnimatedContent(
            targetState = showStop,
            transitionSpec = {
                val duration = if (targetState) {
                    PlaybackViewModel.DEFAULT_FADE_IN_MS.toInt()
                } else {
                    PlaybackViewModel.DEFAULT_FADE_OUT_MS.toInt()
                }
                fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
            },
            modifier = Modifier.align(BiasAlignment(0f, -0.33f)),
        ) { isStop ->
            if (isStop) {
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
                // Rounded-corner play triangle
                Canvas(
                    modifier = Modifier
                        .size(120.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPlay() },
                ) {
                    drawIntoCanvas { canvas ->
                        val paint = NativePaint().apply {
                            setColor(MutedBlue.toArgb())
                            style = NativePaint.Style.FILL
                            pathEffect = CornerPathEffect(16.dp.toPx())
                            isAntiAlias = true
                        }
                        val path = android.graphics.Path().apply {
                            moveTo(size.width * 0.22f, size.height * 0.12f)
                            lineTo(size.width * 0.88f, size.height * 0.50f)
                            lineTo(size.width * 0.22f, size.height * 0.88f)
                            close()
                        }
                        canvas.nativeCanvas.drawPath(path, paint)
                    }
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

        // Timer countdown (subtle, below play button when armed)
        if (timerState is TimerState.Armed && !showTimerLabel) {
            val remaining = timerState.remainingMs
            val minutes = remaining / 60_000
            val seconds = (remaining % 60_000) / 1000
            Text(
                text = "%d:%02d".format(minutes, seconds),
                color = MutedBlue.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(BiasAlignment(0f, -0.12f)),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    texture: Float,
    onTextureChanged: (Float) -> Unit,
    stereoWidth: Float,
    onStereoWidthChanged: (Float) -> Unit,
    microDriftDepth: Float,
    onMicroDriftDepthChanged: (Float) -> Unit,
    fadeInMs: Long,
    onFadeInChanged: (Long) -> Unit,
    fadeOutMs: Long,
    onFadeOutChanged: (Long) -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            // ── Sound controls ─────────────────────────────────
            // Texture slider
            SliderSetting(
                title = "Texture (smooth \u2194 grainy)",
                value = texture,
                onValueChange = onTextureChanged,
            )

            Spacer(Modifier.height(20.dp))

            // Micro drift slider
            SliderSetting(
                title = "Micro drift (none \u2194 max)",
                value = microDriftDepth,
                onValueChange = onMicroDriftDepthChanged,
            )

            Spacer(Modifier.height(20.dp))

            // Stereo width slider
            SliderSetting(
                title = "Stereo (none \u2194 wide)",
                value = stereoWidth,
                onValueChange = onStereoWidthChanged,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = DimBlue)
            Spacer(Modifier.height(24.dp))

            // ── Fade durations ─────────────────────────────────
            FadePicker(
                label = "Fade in",
                selectedMs = fadeInMs,
                onSelected = onFadeInChanged,
            )

            Spacer(Modifier.height(16.dp))

            FadePicker(
                label = "Fade out",
                selectedMs = fadeOutMs,
                onSelected = onFadeOutChanged,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = DimBlue)
            Spacer(Modifier.height(24.dp))

            // ── About ──────────────────────────────────────────
            Text(
                text = "The Noise Machine generates ambient noise to mask " +
                    "distractions and help you sleep.",
                color = MutedBlue.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "The Color slider on the main screen shapes the tone " +
                    "from bright (AKA white noise) to balanced (AKA pink noise) " +
                    "to deep (AKA brown noise).",
                color = MutedBlue.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Made by The Moving Finger Studios",
                color = MutedBlue.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "In memoriam Sergei Skarupo, 1973\u20132021",
                color = MutedBlue.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Text(title, color = MutedBlue, style = MaterialTheme.typography.titleMedium)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        colors = SliderDefaults.colors(
            thumbColor = MutedBlue,
            activeTrackColor = MutedBlue,
            inactiveTrackColor = DimBlue,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FadePicker(
    label: String,
    selectedMs: Long,
    onSelected: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = fadeOptions.firstOrNull { it.ms == selectedMs }?.label
        ?: "${selectedMs / 1000}s"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MutedBlue, style = MaterialTheme.typography.titleMedium)

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedLabel, color = MutedBlue)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                for (option in fadeOptions) {
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelected(option.ms)
                            expanded = false
                        },
                    )
                }
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
            initialTimerIndex = 0,
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
            initialTimerIndex = 2,
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
        SettingsScreen(
            texture = 0.3f,
            onTextureChanged = {},
            stereoWidth = 0.3f,
            onStereoWidthChanged = {},
            microDriftDepth = 0.2f,
            onMicroDriftDepthChanged = {},
            fadeInMs = 2000L,
            onFadeInChanged = {},
            fadeOutMs = 5000L,
            onFadeOutChanged = {},
            onBack = {},
        )
    }
}
