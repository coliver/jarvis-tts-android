package com.jarvistts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val BgColor = Color(0xFF0A0E13)
private val SurfaceColor = Color(0xFF171D24)
private val DividerColor = Color(0xFF232B33)
private val TextPrimary = Color(0xFFE7ECEF)
private val TextDim = Color(0xFF6E7A87)
private val Accent = Color(0xFF4FD6C4)
private val AccentDim = Color(0xFF2B4A47)
private val Warn = Color(0xFFE2725B)

// Subtle cockpit-glow vignette instead of a flat fill: a faint lift near the
// center of the screen, resolved against the actual layout size at draw
// time (Offset.Unspecified/infinite radius both mean "fit to bounds").
private val BgGradient = Brush.radialGradient(colors = listOf(Color(0xFF141C24), BgColor))

private val CondensedFamily =
    FontFamily(Font(familyName = androidx.compose.ui.text.font.DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Medium))
private val MonoFamily = FontFamily.Monospace

enum class Phase { WARMING_UP, IDLE, LISTENING, THINKING, SPEAKING, SWITCHING_MODEL, ERROR }

enum class Speaker { USER, JARVIS }

data class Turn(val speaker: Speaker, val text: String, val durationMs: Long)

/** A pending confirmation before an initial model download proceeds over a
 *  metered connection (see MainActivity.confirmMeteredDownloadIfNeeded).
 *  Non-null shows the dialog; the two callbacks resolve the coroutine
 *  suspended waiting on the user's choice.
 */
data class MeteredDownloadPrompt(
    val label: String,
    val sizeMb: Int,
    val onProceed: () -> Unit,
    val onCancel: () -> Unit,
)

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
    var lowMemoryWarning by mutableStateOf<String?>(null)
    var meteredDownloadPrompt by mutableStateOf<MeteredDownloadPrompt?>(null)
    var availableModels by mutableStateOf<List<String>>(emptyList())
    var selectedModelName by mutableStateOf("")
    var availableVoices by mutableStateOf<List<String>>(emptyList())
    var selectedVoiceName by mutableStateOf("")
    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
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
    onVoiceSelect: (String) -> Unit = {},
    onPauseToggle: () -> Unit = {},
    onStopTap: () -> Unit = {},
    onNewSession: () -> Unit = {},
    onSessionSelect: (String) -> Unit = {},
    onSessionDelete: (String) -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(BgGradient)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        TopBar(state, onModelSelect, onVoiceSelect, onNewSession, onSessionSelect, onSessionDelete)
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

        state.lowMemoryWarning?.let { warning ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = warning,
                color = Warn,
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.clickable { state.lowMemoryWarning = null },
            )
        }
        Spacer(Modifier.height(12.dp))
        MicControl(state, micEnabled, onMicTap, onPauseToggle, onStopTap)
        Spacer(Modifier.height(28.dp))
    }

    state.meteredDownloadPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = prompt.onCancel,
            title = { Text("Not on Wi-Fi", fontFamily = MonoFamily) },
            text = {
                Text(
                    "Downloading the ${prompt.label} (~${prompt.sizeMb}MB) over this connection " +
                        "may use mobile data. Continue?",
                    fontFamily = MonoFamily,
                )
            },
            confirmButton = {
                TextButton(onClick = prompt.onProceed) { Text("Download anyway", fontFamily = MonoFamily) }
            },
            dismissButton = {
                TextButton(onClick = prompt.onCancel) { Text("Wait for Wi-Fi", fontFamily = MonoFamily) }
            },
        )
    }
}

@Composable
private fun TopBar(
    state: JarvisUiState,
    onModelSelect: (String) -> Unit,
    onVoiceSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onSessionSelect: (String) -> Unit,
    onSessionDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
            }
        }
        if (state.phase != Phase.WARMING_UP) {
            // A full-width row of its own, not squeezed next to the title: on a
            // portrait phone, History + New + the model filename + the voice
            // name don't fit in the half-row this used to share with "JARVIS",
            // which pushed VoicePicker (and its dropdown) off-screen entirely.
            // horizontalScroll is a safety net for a long model filename.
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryPicker(state, onNewSession, onSessionSelect, onSessionDelete)
                Spacer(Modifier.width(14.dp))
                ModelPicker(state, onModelSelect)
                Spacer(Modifier.width(14.dp))
                VoicePicker(state, onVoiceSelect)
            }
        }
    }
}

/** Session history: "New" starts a fresh conversation, "History" lists
 *  saved ones (tap a row to resume it, tap its "x" to delete it). Both are
 *  only enabled while idle, same reasoning as ModelPicker: switching
 *  conversations mid-turn would yank the transcript out from under an
 *  in-flight listen/think/speak cycle.
 */
@Composable
private fun HistoryPicker(
    state: JarvisUiState,
    onNewSession: () -> Unit,
    onSessionSelect: (String) -> Unit,
    onSessionDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = state.phase == Phase.IDLE
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "New",
            color = if (enabled) TextDim else TextDim.copy(alpha = 0.5f),
            fontFamily = MonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.clickable(enabled = enabled) { onNewSession() },
        )
        Spacer(Modifier.width(10.dp))
        Box {
            val historyEnabled = enabled && state.sessions.isNotEmpty()
            Text(
                text = "History",
                color = if (historyEnabled) TextDim else TextDim.copy(alpha = 0.5f),
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = historyEnabled) { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (session in state.sessions) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = session.title,
                                    fontFamily = MonoFamily,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "x",
                                    color = Warn,
                                    fontFamily = MonoFamily,
                                    fontSize = 13.sp,
                                    modifier =
                                        Modifier.clickable {
                                            expanded = false
                                            onSessionDelete(session.id)
                                        },
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSessionSelect(session.id)
                        },
                    )
                }
            }
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

/** Bare-bones voice picker, same shape as ModelPicker: tap the current voice
 *  name to pick a different cloned-voice reference clip. Switching voices
 *  never touches ttsHandle (the name is just passed to streamStart per
 *  utterance), so unlike the model picker there's no reload in flight, but
 *  it's still idle-gated for UI consistency with the other pickers.
 */
@Composable
private fun VoicePicker(
    state: JarvisUiState,
    onVoiceSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = state.phase == Phase.IDLE && state.availableVoices.size > 1
    Box {
        Text(
            text = state.selectedVoiceName,
            color = if (enabled) TextDim else TextDim.copy(alpha = 0.5f),
            fontFamily = MonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.clickable(enabled = enabled) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (name in state.availableVoices) {
                DropdownMenuItem(
                    text = { Text(name, fontFamily = MonoFamily, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onVoiceSelect(name)
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
            val textColor = if (isUser) TextDim else TextPrimary
            for (block in remember(turn.text) { Markdown.parse(turn.text) }) {
                when (block) {
                    is MarkdownBlock.Paragraph ->
                        Text(
                            text = paragraphAnnotatedString(block.spans, textColor),
                            fontSize = 17.sp,
                            fontWeight = if (isUser) FontWeight.Normal else FontWeight.Medium,
                            lineHeight = 23.sp,
                            textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                        )
                    is MarkdownBlock.CodeBlock ->
                        Box(
                            modifier =
                                Modifier
                                    .padding(vertical = 4.dp)
                                    .border(width = 1.dp, color = DividerColor)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                        ) {
                            Text(block.code, color = TextPrimary, fontFamily = MonoFamily, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                }
            }
        }
    }
}

private fun paragraphAnnotatedString(
    spans: List<MarkdownSpan>,
    baseColor: Color,
) = buildAnnotatedString {
    for (span in spans) {
        when (span) {
            is MarkdownSpan.Plain -> withStyle(SpanStyle(color = baseColor)) { append(span.text) }
            is MarkdownSpan.Bold -> withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Bold)) { append(span.text) }
            is MarkdownSpan.Code -> withStyle(SpanStyle(color = Accent, fontFamily = MonoFamily)) { append(span.text) }
        }
    }
}

private const val RING_DIAMETER_DP = 176
private const val HOLD_TO_STOP_MS = 550

/** The one hero moment on screen: a radial voiceprint ring rather than the
 *  soft glowing blob every other voice assistant uses. Ticks fan out from a
 *  fixed circle instead of bending it, and THINKING gets its own rotating
 *  radar sweep -- a genuinely different motion, not just a recolor, reserved
 *  for the one phase whose duration is truly unknown (LLM generation).
 */
@Composable
private fun MicControl(
    state: JarvisUiState,
    enabled: Boolean,
    onTap: () -> Unit,
    onPauseToggle: () -> Unit,
    onStopTap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holdProgress = remember { Animatable(0f) }
    // Safety net so a completed or interrupted hold never leaves a stale red
    // ring showing once the phase has actually moved on (e.g. right after
    // stop fires and the phase snaps back to IDLE).
    LaunchedEffect(state.phase) { holdProgress.snapTo(0f) }
    val transition = rememberInfiniteTransition(label = "wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )
    val sweepT by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    // The ring itself is the control in every phase -- tap to talk when idle,
    // tap to pause / hold to stop while speaking, and hold to stop while
    // listening or thinking -- rather than a separate button appearing
    // underneath it. No phase leaves the ring dimmed and inert once it has
    // some gesture that applies.
    val isSpeaking = state.phase == Phase.SPEAKING
    val isListening = state.phase == Phase.LISTENING
    val isThinking = state.phase == Phase.THINKING
    val ringInteractive = enabled || isSpeaking || isListening || isThinking
    // The one enable/disable transition worth animating: engines coming
    // online at the end of warm-up should read as the ring waking up, not a
    // hard cut.
    val ringAlpha by animateFloatAsState(if (ringInteractive) 1f else 0.45f, label = "ringAlpha")

    Column(
        modifier = Modifier.fillMaxWidth().alpha(ringAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val holdToStop = isSpeaking || isListening || isThinking
        Canvas(
            modifier =
                Modifier
                    .size(RING_DIAMETER_DP.dp)
                    .then(
                        if (holdToStop) {
                            Modifier.pointerInput(isSpeaking) {
                                detectTapGestures(
                                    onPress = {
                                        var stopTriggered = false
                                        val job =
                                            scope.launch {
                                                holdProgress.snapTo(0f)
                                                holdProgress.animateTo(1f, tween(HOLD_TO_STOP_MS, easing = LinearEasing))
                                                stopTriggered = true
                                                onStopTap()
                                            }
                                        tryAwaitRelease()
                                        job.cancel()
                                        holdProgress.snapTo(0f)
                                        if (!stopTriggered && isSpeaking) onPauseToggle()
                                    },
                                )
                            }
                        } else {
                            Modifier.clickable(enabled = enabled) { onTap() }
                        },
                    ),
        ) {
            val barCount = WAVE_BARS
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f * 0.5f
            val maxTickLen = size.minDimension / 2f * 0.34f
            // Listening only shows real mic amplitude while actually recording; once
            // recording stops the caption moves on to "Transcribing" (or a model
            // load) but the phase doesn't change until the whole turn is scored, so
            // without this the ring would freeze on the last recorded frame for
            // however long whisper/llama take to run.
            val isBusyWait =
                (state.phase == Phase.LISTENING && state.caption != "Listening") ||
                    state.phase == Phase.THINKING ||
                    state.phase == Phase.SWITCHING_MODEL
            val ringColor =
                when {
                    state.phase == Phase.LISTENING || state.phase == Phase.SPEAKING -> Accent
                    isBusyWait -> Accent.copy(alpha = 0.8f)
                    state.phase == Phase.ERROR -> Warn
                    else -> TextDim.copy(alpha = 0.5f)
                }
            drawCircle(color = ringColor.copy(alpha = 0.3f), radius = baseRadius, center = center, style = Stroke(width = 1.5.dp.toPx()))
            if (holdProgress.value > 0f) {
                // Traces the same base circle rather than adding a separate
                // shape: holding to stop reads as claiming the ring itself,
                // closing into a full red circle right as the stop fires.
                drawArc(
                    color = Warn,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress.value,
                    useCenter = false,
                    topLeft = center - Offset(baseRadius, baseRadius),
                    size = Size(baseRadius * 2f, baseRadius * 2f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
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
                val tickLen = level.coerceIn(0f, 1f) * maxTickLen
                val angle = (i / barCount.toFloat()) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val dir = Offset(cos(angle), sin(angle))
                drawLine(
                    color = ringColor,
                    start = center + dir * baseRadius,
                    end = center + dir * (baseRadius + tickLen),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            if (isBusyWait) {
                val sweepAngle = sweepT * 2f * PI.toFloat()
                val sweepDir = Offset(cos(sweepAngle), sin(sweepAngle))
                drawLine(
                    color = Accent,
                    start = center,
                    end = center + sweepDir * (baseRadius + maxTickLen),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            drawCircle(color = ringColor, radius = 3.dp.toPx(), center = center)
        }
        Spacer(Modifier.height(14.dp))
        val showsElapsed = state.phase == Phase.LISTENING || state.phase == Phase.THINKING || state.phase == Phase.SPEAKING
        Text(
            text = if (showsElapsed) "${state.caption} ${state.stageElapsedSeconds}s" else state.caption,
            color = TextDim,
            fontFamily = MonoFamily,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        // Fixed-height slot regardless of phase, so the ring above never
        // shifts as this hint's text comes and goes -- it used to jump up on
        // LISTENING once a control appeared underneath it, since this whole
        // column sits below a weight(1f) transcript that absorbs the
        // difference.
        Box(modifier = Modifier.height(BELOW_CAPTION_HEIGHT_DP.dp), contentAlignment = Alignment.TopCenter) {
            val hint =
                when {
                    isSpeaking && state.isPaused -> "Tap to resume, hold to stop"
                    isSpeaking -> "Tap to pause, hold to stop"
                    isListening || isThinking -> "Hold to stop"
                    else -> null
                }
            if (hint != null) {
                Text(
                    text = hint,
                    color = TextDim.copy(alpha = 0.7f),
                    fontFamily = MonoFamily,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val BELOW_CAPTION_HEIGHT_DP = 20
