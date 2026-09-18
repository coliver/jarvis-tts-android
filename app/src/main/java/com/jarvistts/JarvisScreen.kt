package com.jarvistts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

private val BgColor = Color(0xFF0A0E13)
private val SurfaceColor = Color(0xFF171D24)
private val DividerColor = Color(0xFF232B33)
private val TextPrimary = Color(0xFFE7ECEF)
private val TextDim = Color(0xFF6E7A87)
private val Accent = Color(0xFF4FD6C4)
private val AccentDim = Color(0xFF2B4A47)
private val Warn = Color(0xFFE2725B)

private val CondensedFamily =
    FontFamily(Font(familyName = androidx.compose.ui.text.font.DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Medium))
private val MonoFamily = FontFamily.Monospace

enum class Phase { WARMING_UP, IDLE, LISTENING, THINKING, SPEAKING, SWITCHING_MODEL, ERROR }

enum class Speaker { USER, JARVIS }

data class Turn(val speaker: Speaker, val text: String, val durationMs: Long)

private fun formatDuration(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)

private const val WAVE_BARS = 40

class JarvisUiState {
    var phase by mutableStateOf(Phase.WARMING_UP)
    var sttState by mutableStateOf("pending")
    var llmState by mutableStateOf("pending")
    var ttsState by mutableStateOf("pending")
    var elapsedSeconds by mutableStateOf(0)
    var caption by mutableStateOf("Warming up")
    var stageElapsedSeconds by mutableStateOf(0)
    var sttProgress by mutableStateOf(0f)
    var llmProgress by mutableStateOf(0f)
    var isPaused by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var availableModels by mutableStateOf<List<String>>(emptyList())
    var selectedModelName by mutableStateOf("")
    val turns = mutableStateListOf<Turn>()
    val amplitude = mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } }

    fun pushAmplitude(level: Float) {
        if (amplitude.size >= WAVE_BARS) amplitude.removeAt(0)
        amplitude.add(level.coerceIn(0f, 1f))
    }
}

fun engineTint(state: String): Color =
    when {
        state == "ready" -> TextPrimary
        state == "pending" -> TextDim
        else -> Accent
    }

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = BgColor,
                surface = SurfaceColor,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
                primary = Accent,
            ),
        content = content,
    )
}

@Composable
fun JarvisScreen(
    state: JarvisUiState,
    micEnabled: Boolean,
    onMicTap: () -> Unit,
    onModelSelect: (String) -> Unit = {},
    onPauseToggle: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BgColor)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        TopBar(state, onModelSelect)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
        Spacer(Modifier.height(18.dp))

        if (state.phase == Phase.WARMING_UP) {
            TelemetryRow(state)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Something to do while the engines spin up",
                color = TextDim,
                fontFamily = MonoFamily,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            BreakoutGame(modifier = Modifier.weight(1f))
        } else {
            TranscriptLog(state, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        MicControl(state, micEnabled, onMicTap, onPauseToggle)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(
    state: JarvisUiState,
    onModelSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "JARVIS",
            color = TextPrimary,
            fontFamily = CondensedFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            letterSpacing = 3.sp,
        )
        if (state.phase == Phase.WARMING_UP) {
            Text(
                text = "${state.elapsedSeconds}s",
                color = TextDim,
                fontFamily = MonoFamily,
                fontSize = 13.sp,
            )
        } else {
            ModelPicker(state, onModelSelect)
        }
    }
}

/** Bare-bones model picker: tap the current model's filename to pick a
 *  different .gguf from ModelManager.listAvailableModels. Only enabled while
 *  idle, so it can't yank the handle out from under an in-flight turn.
 */
@Composable
private fun ModelPicker(
    state: JarvisUiState,
    onModelSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = state.phase == Phase.IDLE && state.availableModels.size > 1
    Box {
        Text(
            text = state.selectedModelName,
            color = if (enabled) TextDim else TextDim.copy(alpha = 0.5f),
            fontFamily = MonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.clickable(enabled = enabled) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (name in state.availableModels) {
                DropdownMenuItem(
                    text = { Text(name, fontFamily = MonoFamily, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onModelSelect(name)
                    },
                )
            }
        }
    }
}

@Composable
private fun TelemetryRow(state: JarvisUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TelemetryCell("Speech", state.sttState, state.sttProgress, Modifier.weight(1f))
        TelemetryCell("Language", state.llmState, state.llmProgress, Modifier.weight(1f))
        TelemetryCell("Voice", state.ttsState, null, Modifier.weight(1f))
    }
}

@Composable
private fun TelemetryCell(
    label: String,
    value: String,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = TextDim, fontFamily = MonoFamily, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = engineTint(value), fontFamily = MonoFamily, fontSize = 13.sp)
        if (progress != null && value.contains("...")) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(TextDim.copy(alpha = 0.25f))) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(Accent),
                )
            }
        }
    }
}

@Composable
private fun TranscriptLog(
    state: JarvisUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.size - 1)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(state.turns) { turn -> TurnRow(turn) }
        item {
            if (state.errorMessage != null) {
                Spacer(Modifier.height(10.dp))
                Text(state.errorMessage!!, color = Warn, fontFamily = MonoFamily, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TurnRow(turn: Turn) {
    val isUser = turn.speaker == Speaker.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(Modifier.width(12.dp), contentAlignment = Alignment.TopStart) {
                Box(Modifier.width(2.dp).height(18.dp).background(Accent))
            }
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(
                text =
                    if (isUser) {
                        "${formatDuration(turn.durationMs)} You"
                    } else {
                        "Jarvis ${formatDuration(turn.durationMs)}"
                    },
                color = TextDim,
                fontFamily = MonoFamily,
                fontSize = 11.sp,
            )
            Text(
                text = turn.text,
                color = if (isUser) TextDim else TextPrimary,
                fontSize = 17.sp,
                fontWeight = if (isUser) FontWeight.Normal else FontWeight.Medium,
                lineHeight = 23.sp,
                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun MicControl(
    state: JarvisUiState,
    enabled: Boolean,
    onTap: () -> Unit,
    onPauseToggle: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onTap() }
                .alpha(if (enabled) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
        ) {
            val barCount = WAVE_BARS
            val gap = size.width / barCount
            // Listening only shows real mic amplitude while actually recording; once
            // recording stops the caption moves on to "Transcribing" (or a model
            // load) but the phase doesn't change until the whole turn is scored, so
            // without this the bars would freeze on the last recorded frame for
            // however long whisper/llama take to run.
            val isBusyWait =
                (state.phase == Phase.LISTENING && state.caption != "Listening") ||
                    state.phase == Phase.THINKING ||
                    state.phase == Phase.SWITCHING_MODEL
            val barColor =
                when {
                    state.phase == Phase.LISTENING || state.phase == Phase.SPEAKING -> Accent
                    isBusyWait -> Accent.copy(alpha = 0.8f)
                    state.phase == Phase.ERROR -> Warn
                    else -> TextDim.copy(alpha = 0.5f)
                }
            for (i in 0 until barCount) {
                val level =
                    when {
                        state.phase == Phase.LISTENING && !isBusyWait -> state.amplitude.getOrElse(i) { 0f }
                        isBusyWait -> (sin((i / barCount.toFloat()) * 4 * PI + t * 2 * PI).toFloat() * 0.5f + 0.5f) * 0.55f + 0.08f
                        state.phase == Phase.SPEAKING ->
                            (sin((i / barCount.toFloat()) * 8 * PI + t * 4 * PI).toFloat() * 0.5f + 0.5f) * 0.7f + 0.1f
                        // WARMING_UP, IDLE, ERROR: a slow breathing pulse so the screen
                        // never sits perfectly still, even with nothing actively running.
                        else -> 0.12f + 0.05f * (sin(t * 2 * PI).toFloat() * 0.5f + 0.5f)
                    }
                val barHeight = (level.coerceIn(0f, 1f)) * size.height
                val x = gap * i + gap / 2f
                drawLine(
                    color = barColor,
                    start = Offset(x, size.height / 2f - barHeight / 2f),
                    end = Offset(x, size.height / 2f + barHeight / 2f),
                    strokeWidth = gap * 0.5f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val showsElapsed = state.phase == Phase.LISTENING || state.phase == Phase.THINKING || state.phase == Phase.SPEAKING
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (showsElapsed) "${state.caption} ${state.stageElapsedSeconds}s" else state.caption,
                color = TextDim,
                fontFamily = MonoFamily,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (state.phase == Phase.SPEAKING) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (state.isPaused) "Resume" else "Pause",
                    color = Accent,
                    fontFamily = MonoFamily,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onPauseToggle() },
                )
            }
        }
    }
}
