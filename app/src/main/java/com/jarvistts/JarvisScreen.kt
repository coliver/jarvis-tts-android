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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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

// 4.43:1 against BgColor, just under WCAG AA's 4.5:1 for normal text; nudged
// lighter to clear it (~5.3:1) without changing the muted look meaningfully.
private val TextDim = Color(0xFF7B8794)
private val Accent = Color(0xFF4FD6C4)
private val BubbleColor = Color(0xFF1A232C)
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

private const val HERO_RING_DP = 232
private const val DOCK_RING_DP = 148

@OptIn(ExperimentalMaterial3Api::class)
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
    onSessionDeleteAll: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showSettings by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                state = state,
                onNewSession = {
                    scope.launch { drawerState.close() }
                    onNewSession()
                },
                onSessionSelect = {
                    scope.launch { drawerState.close() }
                    onSessionSelect(it)
                },
                onSessionDelete = onSessionDelete,
                onDeleteAll = { confirmDeleteAll = true },
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BgGradient)
                    .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            TopBar(
                state = state,
                onMenu = { scope.launch { drawerState.open() } },
                onSettings = { showSettings = true },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

            val isEmptyConversation = state.turns.isEmpty() && state.phase != Phase.WARMING_UP
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                when {
                    state.phase == Phase.WARMING_UP -> {
                        Spacer(Modifier.height(18.dp))
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
                    }
                    isEmptyConversation ->
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MicControl(state, micEnabled, onMicTap, onPauseToggle, onStopTap, HERO_RING_DP.dp)
                        }
                    else -> TranscriptLog(state, modifier = Modifier.weight(1f))
                }

                state.lowMemoryWarning?.let { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = warning,
                        color = Warn,
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        modifier =
                            Modifier
                                .clickable { state.lowMemoryWarning = null }
                                .semantics {
                                    onClick(label = "Dismiss warning") {
                                        state.lowMemoryWarning = null
                                        true
                                    }
                                },
                    )
                }
            }
            if (!isEmptyConversation) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                Spacer(Modifier.height(10.dp))
                MicControl(state, micEnabled, onMicTap, onPauseToggle, onStopTap, DOCK_RING_DP.dp)
                Spacer(Modifier.height(16.dp))
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = SurfaceColor,
            contentColor = TextPrimary,
        ) {
            SettingsSheet(
                state = state,
                onModelSelect = {
                    showSettings = false
                    onModelSelect(it)
                },
                onVoiceSelect = {
                    showSettings = false
                    onVoiceSelect(it)
                },
            )
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all history?") },
            text = { Text("This permanently deletes all ${state.sessions.size} saved conversations.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        scope.launch { drawerState.close() }
                        onSessionDeleteAll()
                    },
                ) { Text("Delete all", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }

    state.meteredDownloadPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = prompt.onCancel,
            title = { Text("Not on Wi-Fi") },
            text = {
                Text(
                    "Downloading the ${prompt.label} (~${prompt.sizeMb}MB) over this connection " +
                        "may use mobile data. Continue?",
                )
            },
            confirmButton = { TextButton(onClick = prompt.onProceed) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = prompt.onCancel) { Text("Wait for Wi-Fi") } },
        )
    }
}

private enum class GlyphKind { MENU, TUNE, CLOSE, PLUS }

/** 48dp touch target drawing its own glyph, so the app needs no icon library. */
@Composable
private fun GlyphButton(
    kind: GlyphKind,
    description: String,
    enabled: Boolean = true,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.35f)
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = 1.8.dp.toPx()
            val cap = StrokeCap.Round
            val s = size.width
            when (kind) {
                GlyphKind.MENU -> {
                    drawLine(color, Offset(2f, s * 0.25f), Offset(s - 2f, s * 0.25f), w, cap)
                    drawLine(color, Offset(2f, s * 0.5f), Offset(s * 0.65f, s * 0.5f), w, cap)
                    drawLine(color, Offset(2f, s * 0.75f), Offset(s - 2f, s * 0.75f), w, cap)
                }
                GlyphKind.TUNE -> {
                    drawLine(color, Offset(2f, s * 0.32f), Offset(s - 2f, s * 0.32f), w, cap)
                    drawLine(color, Offset(2f, s * 0.68f), Offset(s - 2f, s * 0.68f), w, cap)
                    drawCircle(BgColor, radius = 3.5.dp.toPx(), center = Offset(s * 0.68f, s * 0.32f))
                    drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(s * 0.68f, s * 0.32f), style = Stroke(w))
                    drawCircle(BgColor, radius = 3.5.dp.toPx(), center = Offset(s * 0.32f, s * 0.68f))
                    drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(s * 0.32f, s * 0.68f), style = Stroke(w))
                }
                GlyphKind.CLOSE -> {
                    drawLine(color, Offset(s * 0.2f, s * 0.2f), Offset(s * 0.8f, s * 0.8f), w, cap)
                    drawLine(color, Offset(s * 0.8f, s * 0.2f), Offset(s * 0.2f, s * 0.8f), w, cap)
                }
                GlyphKind.PLUS -> {
                    drawLine(color, Offset(s * 0.5f, s * 0.15f), Offset(s * 0.5f, s * 0.85f), w, cap)
                    drawLine(color, Offset(s * 0.15f, s * 0.5f), Offset(s * 0.85f, s * 0.5f), w, cap)
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    state: JarvisUiState,
    onMenu: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(GlyphKind.MENU, "Conversation history", onClick = onMenu)
        Text(
            text = "JARVIS",
            color = TextPrimary,
            fontFamily = CondensedFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        if (state.phase == Phase.WARMING_UP) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text("${state.elapsedSeconds}s", color = TextDim, fontFamily = MonoFamily, fontSize = 13.sp)
            }
        } else {
            GlyphButton(GlyphKind.TUNE, "Model and voice", onClick = onSettings)
        }
    }
}

/** Conversation list in a side drawer, the way ChatGPT and Claude do it.
 *  Starting or resuming a conversation is only enabled while idle: swapping
 *  the transcript mid-turn would yank it out from under an in-flight
 *  listen/think/speak cycle. Deleting is always allowed.
 */
@Composable
private fun HistoryDrawer(
    state: JarvisUiState,
    onNewSession: () -> Unit,
    onSessionSelect: (String) -> Unit,
    onSessionDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val idle = state.phase == Phase.IDLE
    ModalDrawerSheet(drawerContainerColor = SurfaceColor, drawerContentColor = TextPrimary) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = idle, onClick = onNewSession)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (idle) Accent else TextDim
                Canvas(Modifier.size(18.dp)) {
                    drawLine(tint, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(tint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
                }
                Spacer(Modifier.width(14.dp))
                Text("New conversation", color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
            if (state.sessions.isEmpty()) {
                Text(
                    text = "Conversations you have are saved here.",
                    color = TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(state.sessions, key = { it.id }) { session ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = idle) { onSessionSelect(session.id) }
                                    .padding(start = 20.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = session.title,
                                color = if (idle) TextPrimary else TextDim,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                            )
                            GlyphButton(
                                GlyphKind.CLOSE,
                                "Delete conversation ${session.title}",
                                tint = TextDim,
                                onClick = { onSessionDelete(session.id) },
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(DividerColor))
                Text(
                    text = "Delete all history",
                    color = Warn,
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDeleteAll).padding(20.dp),
                )
            }
        }
    }
}

/** Model and voice pickers, merged into one bottom sheet. Idle-gated for the
 *  same reason as the conversation list; switching voices never reloads the
 *  TTS handle (the name is passed per utterance), but the gate keeps the two
 *  pickers behaving alike.
 */
@Composable
private fun SettingsSheet(
    state: JarvisUiState,
    onModelSelect: (String) -> Unit,
    onVoiceSelect: (String) -> Unit,
) {
    val idle = state.phase == Phase.IDLE
    Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
        if (!idle) {
            Text(
                text = "Model and voice can be changed when Jarvis is idle.",
                color = TextDim,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        SettingsGroup("Language model", state.availableModels, state.selectedModelName, idle, onModelSelect)
        SettingsGroup("Voice", state.availableVoices, state.selectedVoiceName, idle, onVoiceSelect)
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    options: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Text(
        text = title,
        color = TextDim,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
    for (name in options) {
        val isSelected = name == selected
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled && !isSelected,
                        role = Role.RadioButton,
                    ) { onSelect(name) }
                    .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) Accent else Color.Transparent),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = name,
                color =
                    if (isSelected) {
                        Accent
                    } else if (enabled) {
                        TextPrimary
                    } else {
                        TextDim
                    },
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
    ) {
        items(state.turns) { turn -> TurnRow(turn) }
        state.errorMessage?.let { message ->
            item { Text(message, color = Warn, fontSize = 14.sp) }
        }
    }
}

/** Jarvis replies read as plain text on the page, marked by the accent
 *  hairline; only the user's words sit in a bubble, so the two voices are
 *  told apart by shape rather than by which edge they hug.
 */
@Composable
private fun TurnRow(turn: Turn) {
    val isUser = turn.speaker == Speaker.USER
    val timing = if (isUser) "spoke ${formatDuration(turn.durationMs)}" else "replied in ${formatDuration(turn.durationMs)}"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (isUser) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        .background(BubbleColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (turn.text.isBlank()) {
                    Text("Nothing heard", color = TextDim, fontSize = 16.sp, lineHeight = 23.sp)
                } else {
                    TurnBody(turn.text, TextPrimary, 16.sp, 23.sp, TextAlign.Start)
                }
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Accent.copy(alpha = 0.7f)))
                Spacer(Modifier.width(14.dp))
                Column { TurnBody(turn.text, TextPrimary, 17.sp, 26.sp, TextAlign.Start) }
            }
        }
        Text(
            text = timing,
            color = TextDim,
            fontFamily = MonoFamily,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp, start = if (isUser) 0.dp else 16.dp),
        )
    }
}

@Composable
private fun TurnBody(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    align: TextAlign,
) {
    for (block in remember(text) { Markdown.parse(text) }) {
        when (block) {
            is MarkdownBlock.Paragraph ->
                Text(
                    text = paragraphAnnotatedString(block.spans, color),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    textAlign = align,
                )
            is MarkdownBlock.CodeBlock ->
                Box(
                    modifier =
                        Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgColor)
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                ) {
                    Text(block.code, color = TextPrimary, fontFamily = MonoFamily, fontSize = 14.sp, lineHeight = 19.sp)
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
    diameter: Dp,
) {
    val scope = rememberCoroutineScope()
    val scale = diameter.value / 176f
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
        // The ring is drawn on a bare Canvas, so it has no accessible name or
        // action by default; the holdToStop branch below also bypasses
        // Modifier.clickable (a raw pointerInput gesture, needed for the
        // hold-to-stop timing), which means TalkBack/switch access get no
        // click action at all unless one is added explicitly here.
        val micAccessibilityLabel =
            when {
                isListening -> "Listening. Double tap to stop."
                isThinking -> "Thinking. Double tap to stop."
                isSpeaking && state.isPaused -> "Paused. Double tap to resume."
                isSpeaking -> "Speaking. Double tap to pause."
                else -> state.caption
            }
        Canvas(
            modifier =
                Modifier
                    .size(diameter)
                    .semantics {
                        contentDescription = micAccessibilityLabel
                        // Only changes on phase transitions, not per-second tick, so
                        // this won't spam TalkBack the way the elapsed-time caption
                        // below would if it were made a live region instead.
                        liveRegion = LiveRegionMode.Polite
                        if (holdToStop) {
                            onClick(label = if (isSpeaking) "Pause or resume" else "Stop") {
                                if (isSpeaking) onPauseToggle() else onStopTap()
                                true
                            }
                        }
                    }
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
            drawCircle(
                color = ringColor.copy(alpha = 0.3f),
                radius = baseRadius,
                center = center,
                style = Stroke(width = (1.5f * scale).coerceAtLeast(1f).dp.toPx()),
            )
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
                    style = Stroke(width = (3f * scale).coerceAtLeast(2f).dp.toPx(), cap = StrokeCap.Round),
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
                    strokeWidth = (2.5f * scale).coerceAtLeast(1.6f).dp.toPx(),
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
                    strokeWidth = (1.5f * scale).coerceAtLeast(1f).dp.toPx(),
                )
            }
            // Idle is the resting state most often seen, and used to rely entirely
            // on the "Tap to talk" caption below to signal what the ring does; a
            // literal mic glyph makes that obvious from the ring alone.
            if (state.phase == Phase.IDLE) {
                drawMicGlyph(center = center, scale = scale, color = ringColor)
            } else {
                drawCircle(color = ringColor, radius = (3f * scale).coerceAtLeast(2f).dp.toPx(), center = center)
            }
        }
        Spacer(Modifier.height(if (scale > 1f) 20.dp else 8.dp))
        val showsElapsed = state.phase == Phase.LISTENING || state.phase == Phase.THINKING || state.phase == Phase.SPEAKING
        // The idle mic glyph now carries "tap to talk" on its own, so the caption
        // row is left blank (not removed) rather than showing that text, keeping
        // this height identical across phases -- see BELOW_CAPTION_HEIGHT_DP for
        // the same reasoning applied to the hint row.
        Text(
            text =
                when {
                    state.phase == Phase.IDLE -> ""
                    showsElapsed -> "${state.caption} ${state.stageElapsedSeconds}s"
                    else -> state.caption
                },
            color = TextDim,
            fontFamily = MonoFamily,
            fontSize = if (scale > 1f) 15.sp else 13.sp,
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
                    color = TextDim,
                    fontFamily = MonoFamily,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val BELOW_CAPTION_HEIGHT_DP = 22

/** A plain line-drawn microphone: capsule body, a cradling stand arc, a
 *  stem, and a base -- the same drawLine/drawArc glyph style as [GlyphButton]
 *  rather than a bundled icon set. Drawn in place of the idle ring's small
 *  center dot so the ring itself reads as "tap to talk" without relying on
 *  the caption text underneath it.
 */
private fun DrawScope.drawMicGlyph(
    center: Offset,
    scale: Float,
    color: Color,
) {
    val bodyWidth = (11f * scale).coerceAtLeast(8f).dp.toPx()
    val bodyHeight = (20f * scale).coerceAtLeast(14f).dp.toPx()
    val strokeWidth = (2.2f * scale).coerceAtLeast(1.6f).dp.toPx()
    val standRadius = bodyWidth / 2f + strokeWidth * 1.6f
    val stemLength = (6f * scale).coerceAtLeast(4f).dp.toPx()
    val baseHalfWidth = (6f * scale).coerceAtLeast(4f).dp.toPx()

    // Shifted up slightly so the body+stand+stem+base group balances around center.
    val bodyCenter = center - Offset(0f, stemLength / 2f + strokeWidth)
    drawRoundRect(
        color = color,
        topLeft = Offset(bodyCenter.x - bodyWidth / 2f, bodyCenter.y - bodyHeight / 2f),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(bodyWidth / 2f),
    )

    val standCenterY = bodyCenter.y + bodyHeight / 2f - standRadius * 0.35f
    drawArc(
        color = color,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(bodyCenter.x - standRadius, standCenterY - standRadius),
        size = Size(standRadius * 2f, standRadius * 2f),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )

    val standBottomY = standCenterY + standRadius
    drawLine(
        color = color,
        start = Offset(bodyCenter.x, standBottomY),
        end = Offset(bodyCenter.x, standBottomY + stemLength),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(bodyCenter.x - baseHalfWidth, standBottomY + stemLength),
        end = Offset(bodyCenter.x + baseHalfWidth, standBottomY + stemLength),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}
