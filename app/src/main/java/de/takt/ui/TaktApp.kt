package de.takt.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.takt.audio.MetronomeController
import de.takt.audio.MetronomeService
import de.takt.AppLanguage
import de.takt.R
import de.takt.core.*
import de.takt.data.SettingsStore
import de.takt.data.AccentColor
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

private val Mint = Color(0xFFB8F2CB)
private val Ink = Color(0xFF0E1213)
private val Paper = Color(0xFFEDF4EF)
private val Muted = Color(0xFFB0BEB7)
private val Edge = Color(0xFF71867D)
private val Panel = Color(0xFF19211F)
private val Scheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink,
    surface = Panel, onSurface = Paper, onBackground = Paper, outline = Edge,
    secondary = Mint, error = Color(0xFFFFB4AB))

@Composable
fun TaktApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val view = remember { store.loadViewPreferences() }
    var pendulum by rememberSaveable { mutableStateOf(view.pendulum) }
    var accentColor by rememberSaveable { mutableStateOf(view.accent) }
    var visualDelayMs by rememberSaveable { mutableIntStateOf(view.visualDelayMs) }
    val accent = when (accentColor) {
        AccentColor.GREEN -> Mint
        AccentColor.ORANGE -> Color(0xFFFFB86B)
        AccentColor.CYAN -> Color(0xFF79E5F2)
    }
    val scheme = remember(accent) { Scheme.copy(primary = accent, secondary = accent,
        primaryContainer = accent.copy(alpha = .20f), onPrimaryContainer = accent,
        secondaryContainer = accent.copy(alpha = .20f), onSecondaryContainer = accent) }
    // Kept independent of the audio/settings schema until the shared view API grows this field.
    val uiStore = remember { context.getSharedPreferences("takt.ui", 0) }
    val systemReduced = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var reduced by rememberSaveable { mutableStateOf(uiStore.getBoolean("reducedMotion", systemReduced)) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val state by MetronomeController.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val rememberedAccents = remember(settings.meter) { mutableMapOf<Int, BeatState>() }
    val update: (Settings) -> Unit = { MetronomeController.update(it) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val toggle: () -> Unit = {
        if (state.running) MetronomeController.stop(context) else {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MetronomeController.start(context)
        }
    }
    val regular = instrumentFont("inter", 400)
    val medium = instrumentFont("inter", 500)
    val brand = instrumentFont("manrope", 600)
    val typography = remember(regular, medium) { instrumentTypography(regular, medium) }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            BoxWithConstraints(Modifier.drawBehind {
                drawRect(Brush.radialGradient(listOf(Color(0xFF1C2725), Ink),
                    center = Offset(size.width / 2, size.height * .33f), radius = size.height * .65f))
            }.safeDrawingPadding().imePadding()) {
                val tapHeight = if (maxHeight < 700.dp) 80.dp else 96.dp
                val fontScale = LocalDensity.current.fontScale
                val statusHeight = if (state.error != null) 64.dp else 0.dp
                val reservedHeight = (148 * fontScale).dp + 48.dp + tapHeight + 36.dp +
                    (if (pendulum) 0.dp else 24.dp) + statusHeight
                val graphSize = minOf(maxWidth - 40.dp, maxHeight - reservedHeight).coerceAtLeast(128.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text("taceto", fontFamily = brand, fontSize = 22.sp, letterSpacing = .6.sp)
                            Text(".", fontFamily = brand, fontSize = 22.sp, color = accent)
                        }
                        if (settings.training.omission != Omission.OFF || settings.training.ramp) {
                            IconButton(onClick = { dialog = "training" }, modifier = Modifier.size(48.dp)
                                .semantics { contentDescription = context.getString(R.string.training_active) }) {
                                TrainingIcon(accent)
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        TextButton(onClick = { dialog = "help" }, modifier = Modifier.size(48.dp).border(1.dp, Paper.copy(alpha = .07f), CircleShape).semantics { contentDescription = context.getString(R.string.open_help) }) { Text("?", color = Muted) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { dialog = "settings" }, modifier = Modifier.size(48.dp).border(1.dp, Paper.copy(alpha = .07f), CircleShape).semantics { contentDescription = context.getString(R.string.open_settings) }) { Text("⚙", fontSize = 20.sp, color = Muted) }
                    }
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                    val heard = state.position
                    val hideVisuals = state.running && !settings.training.visuals && settings.training.omission != Omission.OFF
                    val firstBar = settings.bars.firstOrNull()
                    val idleSettings = firstBar?.applyTo(settings) ?: settings
                    BeatChain(if (hideVisuals) idleSettings else heard?.settings ?: idleSettings, if (state.running && !hideVisuals) heard?.beatIndex else null) { dialog = "rhythm" }
                    if (!pendulum) Spacer(Modifier.height(24.dp))
                    Instrument(idleSettings, if (hideVisuals) null else heard, state.running, pendulum, reduced, Modifier.size(graphSize), toggle)
                    Spacer(Modifier.height(8.dp))
                    Tempo(state.displayedBpm, adjust = { MetronomeController.adjustTempo(it) }) { MetronomeController.setTempo(it) }
                    Spacer(Modifier.height(12.dp))
                    TapButton(reduced, tapHeight) { MetronomeController.setTempo(it) }
                    Spacer(Modifier.height(16.dp))
                }
            }
            when (dialog) {
                "rhythm", "meter", "beats", "training" -> PatternDialog(settings, state.position, update,
                    initialTab = if (dialog == "training") 1 else 0) { dialog = null }
                "settings" -> Sheet(stringResource(R.string.settings), { dialog = null }) {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                    ChoiceRow(listOf(stringResource(R.string.language_system), stringResource(R.string.language_german), stringResource(R.string.language_english)),
                        listOf("", "de", "en").indexOf(AppLanguage.selection(context)).coerceAtLeast(0)) {
                        AppLanguage.select(context, listOf("", "de", "en")[it])
                        if (state.running) context.startService(Intent(context, MetronomeService::class.java).setAction(MetronomeService.ACTION_REFRESH))
                    }
                    Text(stringResource(R.string.color), style = MaterialTheme.typography.titleMedium)
                    ChoiceRow(listOf(stringResource(R.string.green), stringResource(R.string.orange), stringResource(R.string.cyan)), accentColor.ordinal) {
                        accentColor = AccentColor.entries[it]
                        store.saveViewPreferences(store.loadViewPreferences().copy(accent = accentColor))
                    }
                    Text(stringResource(R.string.visualization), style = MaterialTheme.typography.titleMedium)
                    ChoiceRow(listOf(stringResource(R.string.circle), stringResource(R.string.pendulum)), if (pendulum) 1 else 0) {
                        pendulum = it == 1
                        store.saveViewPreferences(store.loadViewPreferences().copy(pendulum = pendulum))
                    }
                    Text(stringResource(R.string.audio_timing), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.visual_delay_value, visualDelayMs))
                    Slider(visualDelayMs.toFloat(), {
                        visualDelayMs = (it / 10).roundToInt() * 10
                        store.saveViewPreferences(store.loadViewPreferences().copy(visualDelayMs = visualDelayMs))
                        MetronomeController.setVisualDelay(visualDelayMs)
                    }, valueRange = 0f..1000f, modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.visual_delay)
                    })
                    TextButton(onClick = { visualDelayMs = 0
                        store.saveViewPreferences(store.loadViewPreferences().copy(visualDelayMs = 0))
                        MetronomeController.setVisualDelay(0)
                    }) { Text(stringResource(R.string.reset)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(reduced, { reduced = it; uiStore.edit().putBoolean("reducedMotion", it).apply() })
                        Text(stringResource(R.string.reduced_motion), Modifier.clickable { reduced = !reduced; uiStore.edit().putBoolean("reducedMotion", reduced).apply() }.padding(8.dp))
                    }
                    Text(stringResource(R.string.sound), style = MaterialTheme.typography.titleMedium)
                    Sound.entries.forEach { sound ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(settings.sound == sound, { update(settings.copy(sound = sound)) })
                            Text(soundName(sound, context), Modifier.weight(1f).clickable { update(settings.copy(sound = sound)) }.padding(vertical = 12.dp))
                            TextButton(onClick = { MetronomeController.preview(context, sound) }, enabled = !state.running,
                                modifier = Modifier.semantics { contentDescription = context.getString(R.string.preview_sound, soundName(sound, context)) }) { Text("▶") }
                        }
                    }
                    if (settings.sound.tonal) {
                        val pitch = settings.pitchFor()
                        Text(stringResource(R.string.pitch_sound, soundName(settings.sound, context)))
                        Text(pluralStringResource(R.plurals.semitones, abs(pitch), "${if (pitch > 0) "+" else ""}$pitch"))
                        Slider(pitch.toFloat(), { value ->
                            update(settings.copy(pitches = settings.pitches + (settings.sound to value.roundToInt())))
                        }, valueRange = -12f..12f, steps = 23,
                            modifier = Modifier.semantics { contentDescription = context.getString(R.string.pitch_semitones) })
                        Row {
                            TextButton(onClick = { update(settings.copy(pitches = settings.pitches + (settings.sound to 0))) }) { Text(stringResource(R.string.reset)) }
                            TextButton(onClick = { MetronomeController.preview(context, settings.sound) }, enabled = !state.running) { Text(stringResource(R.string.preview)) }
                        }
                    }
                }
                "help" -> Sheet(stringResource(R.string.help), { dialog = null }) {
                    Text(stringResource(R.string.tempo), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.help_tempo))
                    Text(stringResource(R.string.help_pulse_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.help_pulse))
                    Text(stringResource(R.string.help_swing_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.help_swing))
                    Text(stringResource(R.string.help_background_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.help_background))
                    Text(stringResource(R.string.help_reduced))
                    Text(stringResource(R.string.help_sounds))
                    Text(stringResource(R.string.pattern_training), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.help_training))
                    Text(stringResource(R.string.help_training_status))
                    Text(stringResource(R.string.audio_timing), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.help_audio_timing))
                }
            }
        }
    }
}

@Composable
private fun TrainingIcon(color: Color) {
    Canvas(Modifier.size(26.dp)) {
        val stroke = size.width * .10f
        drawLine(color, Offset(size.width * .14f, center.y), Offset(size.width * .86f, center.y), stroke, StrokeCap.Round)
        listOf(.16f to .30f, .30f to .18f, .70f to .18f, .84f to .30f).forEach { (x, inset) ->
            drawLine(color, Offset(size.width * x, size.height * inset),
                Offset(size.width * x, size.height * (1 - inset)), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun Sheet(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label -> FilterChip(selected == index, { onSelect(index) }, label = { Text(label) }) }
    }
}

@Composable
private fun BeatChain(settings: Settings, active: Int?, edit: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, 224.dp) // Seven 32-dp slots, including room for the active dot.
        val chainWidth = if (settings.meter.numerator <= 7) width else maxOf(width, 32.dp * settings.meter.numerator)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(active, settings.meter, width) {
            if (chainWidth <= width) scroll.scrollTo(0)
            else if (active != null) scroll.animateScrollTo(with(density) {
                (chainWidth / settings.meter.numerator * (active + .5f) - width / 2).roundToPx()
            }.coerceAtLeast(0))
        }
        Box(Modifier.width(width).height(48.dp).horizontalScroll(scroll).clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit_rhythm), onClick = edit)
            .semantics { contentDescription = context.getString(R.string.rhythm_description, settings.meter.numerator, settings.meter.denominator,
                active?.let { context.getString(R.string.beat_position, it + 1) } ?: context.getString(R.string.stopped)) }) {
            Canvas(Modifier.width(chainWidth).height(48.dp)) {
                val step = size.width / settings.meter.numerator
                if (settings.meter.numerator > 1) drawLine(Edge.copy(alpha = .4f), Offset(step / 2, center.y), Offset(size.width - step / 2, center.y), 1.dp.toPx())
                settings.effectiveBeats.forEachIndexed { i, beat ->
                    val point = Offset(step * (i + .5f), center.y)
                    drawCircle(Ink, 10.dp.toPx(), point)
                    drawCircle(if (i == active) accent else if (beat == BeatState.ACCENT) Paper else Muted,
                        (if (i == active) 10 else 8).dp.toPx(), point,
                        style = if (beat == BeatState.MUTED) Stroke(2.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
                    if (beat == BeatState.ACCENT) drawCircle(Ink, 2.dp.toPx(), point)
                }
            }
        }
    }
}

@Composable
private fun Instrument(requested: Settings, position: MusicalPosition?, running: Boolean, pendulum: Boolean,
    reduced: Boolean, modifier: Modifier, toggle: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var feedbackCount by remember { mutableIntStateOf(0) }
    var feedbackPlay by remember { mutableStateOf(true) }
    val feedbackAlpha = remember { Animatable(0f) }
    LaunchedEffect(feedbackCount) {
        if (feedbackCount > 0) {
            feedbackAlpha.snapTo(1f)
            delay(180)
            if (reduced) { delay(300); feedbackAlpha.snapTo(0f) }
            else feedbackAlpha.animateTo(0f, tween(300))
        }
    }
    val effective = position?.settings ?: requested
    // Immediate grid preview for edits; phase and geometry timing always come from the heard position.
    val preview = if (requested.bars.isNotEmpty()) effective else effective.copy(subdivision = requested.subdivision, swing = requested.swing, swingEnabled = requested.swingEnabled)
    val grid = remember(preview) { PcmRenderer.buildGrid(preview) }
    val markers = remember(preview, position?.pulseIndex, position?.barIndex, position?.settings) { markerPhases(preview, position, grid) }
    val phase = if (running && !reduced) position?.pulsePhase ?: 0.0 else 0.0
    val backwards = !reduced && (position?.pulseIndex ?: 0) % 2L != 0L
    val glow = if (running && position != null) beatLight(position.pulsePhase, effective.bpm, reduced) else 0f
    Canvas(modifier.clickable(role = Role.Button, onClickLabel = stringResource(if (running) R.string.stop_metronome else R.string.start_metronome), onClick = {
        feedbackPlay = !running
        feedbackCount++
        toggle()
    })
        .semantics { contentDescription = context.getString(if (pendulum) R.string.pendulum else R.string.circle_hand) }) {
        val radius = min(size.width, size.height) * .44f
        if (!pendulum) {
            drawCircle(accent.copy(alpha = .025f), radius)
            drawCircle(accent.copy(alpha = glow), radius)
            drawCircle(Paper.copy(alpha = .07f), radius + 6.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(Edge.copy(alpha = .65f), radius, style = Stroke(1.dp.toPx()))
            fun point(p: Double): Offset { val a = p * 2 * PI - PI / 2; return center + Offset(cos(a).toFloat(), sin(a).toFloat()) * radius }
            markers.forEach { drawCircle(Muted, 4.dp.toPx(), point(it)) }
            drawLine(accent, point(0.0), point(0.0) + Offset(0f, 8.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
            drawLine(accent.copy(alpha = .06f), center, center + (point(phase) - center) * .9f, 10.dp.toPx(), StrokeCap.Round)
            drawLine(accent, center, center + (point(phase) - center) * .9f, 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Panel, 5.dp.toPx()); drawCircle(accent, 5.dp.toPx(), style = Stroke(1.dp.toPx()))
        } else {
            val halfAngle = 40f
            val pivot = Offset(size.width / 2, size.height * .08f)
            val length = min((size.width / 2 - 12.dp.toPx()) / sin(halfAngle * PI / 180).toFloat(), size.height * .86f)
            fun point(p: Double): Offset {
                val swing = -cos(PI * p) * if (backwards) -1 else 1
                val a = swing * halfAngle * PI / 180
                return pivot + Offset(sin(a).toFloat(), cos(a).toFloat()) * length
            }
            val topLeft = pivot - Offset(length, length)
            drawArc(accent.copy(alpha = .025f), 90f - halfAngle, halfAngle * 2, true, topLeft, Size(length * 2, length * 2))
            drawArc(accent.copy(alpha = glow), 90f - halfAngle, halfAngle * 2, true, topLeft, Size(length * 2, length * 2))
            drawArc(Edge.copy(alpha = .65f), 90f - halfAngle, halfAngle * 2, false, topLeft, Size(length * 2, length * 2), style = Stroke(1.dp.toPx()))
            markers.forEach { drawCircle(Muted, 4.dp.toPx(), point(it)) }
            drawCircle(Paper, 5.dp.toPx(), point(0.0)); drawCircle(Paper, 5.dp.toPx(), point(1.0))
            drawLine(accent, pivot, point(phase), 2.dp.toPx(), StrokeCap.Round)
            drawLine(accent, pivot + (point(phase) - pivot) * .80f, pivot + (point(phase) - pivot) * .93f, 10.dp.toPx(), StrokeCap.Round)
            drawCircle(Paper, 5.dp.toPx(), pivot)
        }
        if (feedbackAlpha.value > 0f) {
            val alpha = feedbackAlpha.value
            val iconSize = minOf(40.dp.toPx(), size.minDimension * .22f)
            val origin = center - Offset(iconSize / 2, iconSize / 2)
            drawCircle(Ink.copy(alpha = .88f * alpha), iconSize * .9f, center)
            if (feedbackPlay) {
                drawPath(Path().apply {
                    moveTo(origin.x + iconSize * .25f, origin.y)
                    lineTo(origin.x + iconSize, origin.y + iconSize / 2)
                    lineTo(origin.x + iconSize * .25f, origin.y + iconSize)
                    close()
                }, Paper.copy(alpha = alpha))
            } else {
                drawRect(Paper.copy(alpha = alpha), origin + Offset(iconSize * .2f, 0f), Size(iconSize * .2f, iconSize))
                drawRect(Paper.copy(alpha = alpha), origin + Offset(iconSize * .6f, 0f), Size(iconSize * .2f, iconSize))
            }
        }
    }
}

@Composable
private fun Tempo(bpm: Int, adjust: (Int) -> Unit, update: (Int) -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(bpm.toString()) }
    val focus = LocalFocusManager.current
    val latestBpm by rememberUpdatedState(bpm)
    val latestUpdate by rememberUpdatedState(update)
    val density = LocalDensity.current
    var swiping by remember { mutableStateOf(false) }
    val swipeHint = remember { Animatable(0f) }
    LaunchedEffect(swiping) {
        if (swiping) swipeHint.snapTo(1f)
        else if (swipeHint.value > 0f) { delay(250); swipeHint.animateTo(0f, tween(180)) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val tempoSize = minOf(64f, (maxWidth.value - 104f) / (maxOf(3, bpm.toString().length) * .72f * density.fontScale)).coerceAtLeast(20f).sp
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { adjust(-1) }, modifier = Modifier.size(48.dp).background(Paper.copy(alpha = .025f), CircleShape).border(1.dp, Paper.copy(alpha = .08f), CircleShape).semantics { contentDescription = context.getString(R.string.tempo_decrease) }) { Text("−", fontSize = 25.sp, color = Muted) }
        Box(Modifier.weight(1f)
                .pointerInput(Unit) {
                    var drag = TempoDrag(latestBpm)
                    detectVerticalDragGestures(onDragStart = { drag = TempoDrag(latestBpm); swiping = true },
                        onDragEnd = { swiping = false }, onDragCancel = { swiping = false }) { change, amount ->
                        change.consume()
                        drag.move(amount / density.density)?.let(latestUpdate)
                    }
                }.clickable(role = Role.Button, onClickLabel = stringResource(R.string.tempo_input)) { text = bpm.toString(); editing = true }
                .semantics { contentDescription = context.getString(R.string.tempo_description, bpm) }) {
            SegmentTempo(bpm, tempoSize, Paper)
            if (swipeHint.value > 0f) Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).size(18.dp, 40.dp)) {
                val color = accent.copy(alpha = swipeHint.value)
                val stroke = 2.dp.toPx()
                val left = size.width * .2f; val right = size.width * .8f
                drawLine(color, Offset(left, size.height * .25f), Offset(center.x, size.height * .1f), stroke)
                drawLine(color, Offset(center.x, size.height * .1f), Offset(right, size.height * .25f), stroke)
                drawLine(color, Offset(left, size.height * .75f), Offset(center.x, size.height * .9f), stroke)
                drawLine(color, Offset(center.x, size.height * .9f), Offset(right, size.height * .75f), stroke)
            }
        }
        TextButton(onClick = { adjust(1) }, modifier = Modifier.size(48.dp).background(Paper.copy(alpha = .025f), CircleShape).border(1.dp, Paper.copy(alpha = .08f), CircleShape).semantics { contentDescription = context.getString(R.string.tempo_increase) }) { Text("+", fontSize = 25.sp, color = Muted) }
      }
    }
    if (editing) {
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        val valid = text.toIntOrNull()?.let { it in 1..999 } == true
        fun commit() { if (valid) { update(text.toInt()); focus.clearFocus(); editing = false } }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.tempo)) }, text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.bpm_range)) },
                modifier = Modifier.focusRequester(requester).finishOnEnter { commit() }, singleLine = true, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }))
        }, confirmButton = { TextButton(onClick = { commit() }, enabled = valid) { Text(stringResource(R.string.apply)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun TapButton(reduced: Boolean, height: Dp, update: (Int) -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val taps = remember { mutableListOf<Long>() }
    val flash = remember { Animatable(0f) }
    var count by remember { mutableIntStateOf(0) }
    var pointerFeedback by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(count, reduced) {
        if (!reduced && count > 0) { flash.snapTo(1f); flash.animateTo(0f, tween(400)) }
        else flash.snapTo(0f)
    }
    OutlinedButton(onClick = {
        val now = SystemClock.elapsedRealtime()
        if (taps.isNotEmpty() && now - taps.last() > 2500) taps.clear()
        taps.add(now); while (taps.size > 6) taps.removeAt(0)
        if (taps.size > 1) {
            val interval = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            if (interval > 0) update((60000 / interval).roundToInt().coerceIn(1, 999))
        }
        // Keyboard/accessibility clicks have no pointer-down; physical release must not flash again.
        if (!pointerFeedback) count++
        pointerFeedback = false
    }, interactionSource = interaction, modifier = Modifier.size(168.dp, height)
        .pointerInput(Unit) {
            // Observe without consuming: the native button retains clicks, cancellation and semantics.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pointerFeedback = true
                count++
                if (waitForUpOrCancellation(pass = PointerEventPass.Initial) == null) pointerFeedback = false
            }
        }.semantics { contentDescription = context.getString(R.string.tap_tempo) },
        shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .20f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = accent.copy(alpha = .025f + flash.value * .45f), contentColor = Paper)) { Text(stringResource(R.string.tap), letterSpacing = 1.8.sp) }
}

private fun referenceName(value: Double, context: Context): String {
    val notes = listOf(4.0 to R.string.whole_note, 2.0 to R.string.half_note, 1.0 to R.string.quarter_note,
        .5 to R.string.eighth_note, .25 to R.string.sixteenth_note, .125 to R.string.thirty_second_note)
    notes.firstOrNull { abs(it.first - value) < 1e-8 }?.let { return context.getString(it.second) }
    notes.firstOrNull { abs(it.first * 1.5 - value) < 1e-8 }?.let { return context.getString(R.string.dotted_note, context.getString(it.second)) }
    return context.getString(R.string.quarter_units, NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).format(value))
}

private fun soundName(sound: Sound, context: Context) = context.getString(when (sound) {
    Sound.STANDARDCLICK -> R.string.sound_click; Sound.CUT -> R.string.sound_cut; Sound.WOODBLOCK -> R.string.sound_woodblock
    Sound.HIHAT -> R.string.sound_hihat; Sound.SNARE -> R.string.sound_snare; Sound.COWBELL -> R.string.sound_cowbell
})

@Composable
private fun MeterControls(settings: Settings, pulse: Boolean = true, reference: Boolean = true, update: (Settings) -> Unit) {
    val context = LocalContext.current
    val percent = remember(context.resources.configuration.locales[0]) {
        NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).apply {
            minimumFractionDigits = 1; maximumFractionDigits = 1
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var numerator by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.meter.numerator.toString())) }
    var denominator by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(settings.meter.denominator.toString())) }
    var numeratorFocused by remember { mutableStateOf(false) }
    var denominatorFocused by remember { mutableStateOf(false) }
    val n = numerator.text.toIntOrNull()
    val d = denominator.text.toIntOrNull()
    val valid = n != null && n in 1..1024 && d != null && d in 1..1024 && d and (d - 1) == 0
    fun applyMeter(a: Int, b: Int) { update(settings.copy(meter = Meter(a, b), beats = emptyList())) }
    fun done() { if (valid) applyMeter(n!!, d!!); focus.clearFocus(); keyboard?.hide() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow(listOf("4/4", "3/4", "6/8"), listOf(Meter(4, 4), Meter(3, 4), Meter(6, 8)).indexOf(settings.meter)) {
            val m = listOf(Meter(4, 4), Meter(3, 4), Meter(6, 8))[it]
            numerator = TextFieldValue(m.numerator.toString()); denominator = TextFieldValue(m.denominator.toString()); applyMeter(m.numerator, m.denominator)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(numerator, { if (it.text.length <= 4 && it.text.all(Char::isDigit)) numerator = it }, label = { Text(stringResource(R.string.numerator)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.weight(1f)
                    .onFocusChanged { numeratorFocused = it.isFocused; if (it.isFocused) numerator = numerator.copy(selection = TextRange(0, numerator.text.length)) }
                    .selectNumberOnFirstTap({ numeratorFocused }) { numerator = numerator.copy(selection = TextRange(0, numerator.text.length)) }
                    .finishOnEnter { done() },
                keyboardActions = KeyboardActions(onDone = { done() }))
            OutlinedTextField(denominator, { if (it.text.length <= 4 && it.text.all(Char::isDigit)) denominator = it }, label = { Text(stringResource(R.string.denominator)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.weight(1f)
                    .onFocusChanged { denominatorFocused = it.isFocused; if (it.isFocused) denominator = denominator.copy(selection = TextRange(0, denominator.text.length)) }
                    .selectNumberOnFirstTap({ denominatorFocused }) { denominator = denominator.copy(selection = TextRange(0, denominator.text.length)) }
                    .finishOnEnter { done() },
                keyboardActions = KeyboardActions(onDone = { done() }))
        }
        if (!valid) Text(stringResource(R.string.meter_validation), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { applyMeter(n!!, d!!) }, enabled = valid) { Text(stringResource(R.string.apply_meter)) }
        if (pulse) {
        if (reference) {
            ReferenceControls(settings, update)
        }
        Text(stringResource(R.string.subdivision), style = MaterialTheme.typography.titleMedium)
        SubdivisionChoices(settings.subdivision) { update(settings.copy(subdivision = it)) }
        val available = settings.subdivision != Subdivision.NONE
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(settings.swingEnabled, { update(settings.copy(swingEnabled = it)) }, enabled = available)
            Text(stringResource(R.string.swing_value, percent.format(settings.swing)))
            if (available && settings.swingEnabled) SwingGlyph(settings.subdivision == Subdivision.SIXTEENTH,
                stringResource(if (settings.subdivision == Subdivision.SIXTEENTH) R.string.swing_sixteenth_symbol else R.string.swing_eighth_symbol))
        }
        Slider(settings.swing.toFloat(), { update(settings.copy(swing = (it * 100).roundToInt().coerceIn(50, 75) / 100.0)) },
            valueRange = .5f.. .75f, steps = 24, enabled = available && settings.swingEnabled,
            modifier = Modifier.semantics { contentDescription = context.getString(R.string.swing_amount) })
        TextButton(onClick = { update(settings.copy(swing = 2.0 / 3)) }, enabled = available && settings.swingEnabled) { Text(stringResource(R.string.swing_triplet, percent.format(2.0 / 3))) }
        }
    }
}

/** Apply first-focus selection after TextField's own tap-to-position handling, without consuming it. */
private fun Modifier.selectNumberOnFirstTap(isFocused: () -> Boolean, selectAll: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val firstFocus = !isFocused()
        var change = down
        do {
            change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
        } while (change.pressed)
        if (firstFocus && !change.pressed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop) selectAll()
    }
}

/** Consume both hardware key edges; clearing focus on key-down could click a preset on key-up. */
private fun Modifier.finishOnEnter(done: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
        if (it.type == KeyEventType.KeyUp) done()
        true
    } else false
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceControls(settings: Settings, update: (Settings) -> Unit, showTitle: Boolean = true) {
    val context = LocalContext.current
    if (showTitle) Text(stringResource(R.string.bpm_reference), style = MaterialTheme.typography.titleMedium)
    val dotted = listOf(6.0, 3.0, 1.5, .75, .375, .1875).any { abs(it - settings.reference) < 1e-8 }
    val base = if (dotted) settings.reference / 1.5 else settings.reference
    val refs = listOf(4.0, 2.0, 1.0, .5, .25, .125)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        refs.forEach { value -> FilterChip(abs(value - base) < 1e-8,
            onClick = { update(settings.copy(reference = value * if (dotted) 1.5 else 1.0)) },
            label = { Row(verticalAlignment = Alignment.CenterVertically) {
                NoteGlyph(value * if (dotted) 1.5 else 1.0)
                Spacer(Modifier.width(6.dp)); Text(referenceName(value, context))
            } }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(dotted, { update(settings.copy(reference = base * if (it) 1.5 else 1.0)) })
        Text(stringResource(R.string.dotted))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubdivisionChoices(selected: Subdivision, choose: (Subdivision) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Subdivision.entries.forEach { value -> FilterChip(selected == value, { choose(value) }, label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != Subdivision.NONE) {
                    NoteGlyph(if (value == Subdivision.EIGHTH) .5 else .25)
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(when (value) { Subdivision.NONE -> R.string.none; Subdivision.EIGHTH -> R.string.eighths; Subdivision.SIXTEENTH -> R.string.sixteenths }))
            }
        }) }
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, range: IntRange, update: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { input ->
        text = input.filter(Char::isDigit).take(5)
        text.toIntOrNull()?.takeIf { it in range }?.let(update)
    }, label = { Text(label) }, singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().finishOnEnter { focus.clearFocus(); keyboard?.hide() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternDialog(settings: Settings, position: MusicalPosition?, update: (Settings) -> Unit,
    initialTab: Int = 0, dismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val feedback = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) {
        feedback.currentSnackbarData?.dismiss()
        scope.launch { feedback.showSnackbar(message, withDismissAction = true) }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(store.currentPatternName()) }
    var standardTempo by rememberSaveable { mutableIntStateOf(store.defaultTempo(settings.bpm)) }
    LaunchedEffect(name, standardTempo) { store.rememberPattern(name, standardTempo) }
    var library by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editingBar by remember { mutableStateOf(false) }
    var editingReference by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(1) }
    var newMeter by remember { mutableStateOf(Settings()) }
    val bars = settings.bars.ifEmpty { listOf(PatternBar(settings.meter, settings.beats)) }
    val index = selected.coerceIn(bars.indices)
    val bar = bars[index]
    fun replace(values: List<PatternBar>) { update(settings.copy(bars = values)); selected = selected.coerceIn(values.indices) }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun finishInput() { focus.clearFocus(); keyboard?.hide() }
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding().padding(8.dp), shape = RoundedCornerShape(20.dp)) {
          Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pattern_training), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) }
                }
                ChoiceRow(listOf(stringResource(R.string.pattern), stringResource(R.string.training)), tab) { tab = it }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tab == 0) {
                        OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.pattern_name)) }, modifier = Modifier.fillMaxWidth().finishOnEnter { finishInput() },
                            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { finishInput() }))
                        FlowRow {
                            TextButton(onClick = { finishInput(); library = true }) { PatternActionIcon("load"); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.load_pattern)) }
                            TextButton(onClick = {
                                finishInput()
                                val savedName = name.trim()
                                store.savePattern(savedName, settings.copy(bpm = standardTempo)); name = savedName; revision++
                                notify(context.getString(R.string.pattern_saved, savedName))
                            }, enabled = name.isNotBlank()) { PatternActionIcon("save"); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save_pattern)) }
                            TextButton(onClick = { finishInput(); name = ""; standardTempo = settings.bpm; selected = 0; update(settings.copy(bars = listOf(PatternBar()))) }) { PatternActionIcon("new"); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.new_pattern)) }
                        }
                        NumberSetting(stringResource(R.string.standard_tempo), standardTempo, 1..999) { standardTempo = it }
                        TextButton(onClick = { editingReference = true }) {
                            Text("${stringResource(R.string.bpm_reference)}: "); NoteGlyph(settings.reference)
                            Spacer(Modifier.width(6.dp)); Text(referenceName(settings.reference, context))
                        }
                        PatternGrid(bars, settings, index, position,
                            select = { selected = it },
                            open = { selected = it; editingBar = true }, change = { i, value ->
                                selected = i; replace(bars.toMutableList().also { it[i] = value })
                            })
                        TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_bars)) }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { replace(bars.toMutableList().also { it.add(index + 1, bar) }); selected = index + 1 }) { Text(stringResource(R.string.duplicate_bar)) }
                            TextButton(onClick = { replace(bars.toMutableList().also { it[index] = it[index - 1]; it[index - 1] = bar }); selected = index - 1 }, enabled = index > 0) { Text("←") }
                            TextButton(onClick = { replace(bars.toMutableList().also { it[index] = it[index + 1]; it[index + 1] = bar }); selected = index + 1 }, enabled = index < bars.lastIndex) { Text("→") }
                            TextButton(onClick = { replace(bars.toMutableList().also { it.removeAt(index) }) }, enabled = bars.size > 1) { Text(stringResource(R.string.delete_pattern)) }
                        }
                    } else {
                        val t = settings.training
                        fun train(value: Training) { update(settings.copy(training = value)) }
                        Text(stringResource(R.string.omissions), style = MaterialTheme.typography.titleMedium)
                        ChoiceRow(listOf(stringResource(R.string.none), stringResource(R.string.single_beats), stringResource(R.string.whole_bars)), t.omission.ordinal) { train(t.copy(omission = Omission.entries[it])) }
                        if (t.omission != Omission.OFF) {
                            Text(stringResource(R.string.probability, t.probability))
                            Slider(t.probability.toFloat(), { train(t.copy(probability = it.roundToInt())) }, valueRange = 0f..100f)
                            if (t.omission == Omission.BARS) NumberSetting(stringResource(R.string.max_silent_bars), t.maxSilentBars, 1..100) { train(t.copy(maxSilentBars = it)) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(t.visuals, { train(t.copy(visuals = it)) }); Text(stringResource(R.string.training_visuals)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(t.ramp, { train(t.copy(ramp = it, startBpm = settings.bpm, targetBpm = maxOf(settings.bpm, t.targetBpm))) }); Text(stringResource(R.string.tempo_ramp)) }
                        if (t.ramp) {
                            NumberSetting(stringResource(R.string.start_tempo), t.startBpm, 1..999) { train(t.copy(startBpm = it, targetBpm = maxOf(it, t.targetBpm))) }
                            NumberSetting(stringResource(R.string.tempo_step), t.step, 1..999) { train(t.copy(step = it)) }
                            NumberSetting(stringResource(R.string.interval_seconds), t.intervalSeconds, 1..86400) { train(t.copy(intervalSeconds = it)) }
                            NumberSetting(stringResource(R.string.target_tempo), t.targetBpm, t.startBpm..999) { train(t.copy(targetBpm = it)) }
                            val duration = remember(t, settings.bars, settings.meter, settings.reference) { trainingDurationSeconds(settings) / 60.0 }
                            val minutes = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
                            Text(stringResource(R.string.training_duration, minutes.format(duration)))
                            Text(stringResource(R.string.current_tempo, position?.settings?.bpm ?: t.startBpm))
                        }
                    }
                }
            }
            SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).padding(12.dp))
          }
        }
    }
    if (library) Sheet(stringResource(R.string.load_pattern), { library = false }) {
        val names = remember(revision) { store.patternNames() }
        if (names.isEmpty()) Text(stringResource(R.string.no_saved_patterns))
        names.forEach { saved -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val loaded = store.loadPattern(saved); name = saved; standardTempo = loaded.bpm
                selected = 0; update(loaded); library = false
                notify(context.getString(R.string.pattern_loaded, saved))
            }, modifier = Modifier.weight(1f)) { Text(saved) }
            TextButton(onClick = { store.deletePattern(saved); revision++ }) { Text(stringResource(R.string.delete_pattern)) }
        } }
    }
    if (adding) Sheet(stringResource(R.string.add_bars), { adding = false }) {
        NumberSetting(stringResource(R.string.bar_count), count, 1..128) { count = it }
        MeterControls(newMeter, pulse = false) { newMeter = it }
        TextButton(onClick = { replace(bars + List(count) { PatternBar(newMeter.meter, subdivision = settings.subdivision,
            swingEnabled = settings.swingEnabled, swing = settings.swing) }); selected = bars.size; adding = false }) { Text(stringResource(R.string.add_bars)) }
    }
    if (editingBar) Sheet(stringResource(R.string.selected_bar, index + 1), { editingBar = false }) {
        key(index) {
            MeterControls(bar.applyTo(settings), reference = false) { changed ->
                replace(bars.toMutableList().also { it[index] = PatternBar(changed.meter, changed.beats,
                    changed.subdivision, changed.swingEnabled, changed.swing) })
            }
        }
    }
    if (editingReference) Sheet(stringResource(R.string.bpm_reference), { editingReference = false }) {
        ReferenceControls(settings, update, showTitle = false)
    }
}

@Composable
private fun PatternActionIcon(action: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24f
        val path = Path()
        when (action) {
            "load" -> { path.moveTo(3*unit, 7*unit); path.lineTo(10*unit, 7*unit); path.lineTo(12*unit, 10*unit)
                path.lineTo(21*unit, 10*unit); path.lineTo(21*unit, 20*unit); path.lineTo(3*unit, 20*unit); path.close() }
            "save" -> { path.moveTo(4*unit, 3*unit); path.lineTo(17*unit, 3*unit); path.lineTo(21*unit, 7*unit)
                path.lineTo(21*unit, 21*unit); path.lineTo(4*unit, 21*unit); path.close()
                path.moveTo(8*unit, 3*unit); path.lineTo(8*unit, 9*unit); path.lineTo(16*unit, 9*unit); path.lineTo(16*unit, 3*unit)
                path.moveTo(8*unit, 21*unit); path.lineTo(8*unit, 14*unit); path.lineTo(17*unit, 14*unit); path.lineTo(17*unit, 21*unit) }
            else -> { path.moveTo(12*unit, 4*unit); path.lineTo(12*unit, 20*unit)
                path.moveTo(4*unit, 12*unit); path.lineTo(20*unit, 12*unit) }
        }
        drawPath(path, color, style = Stroke(1.8f*unit, cap = StrokeCap.Round))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternGrid(bars: List<PatternBar>, settings: Settings, selected: Int, position: MusicalPosition?,
    select: (Int) -> Unit, open: (Int) -> Unit, change: (Int, PatternBar) -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val beatWidth = 36.dp * fontScale
        val rows = patternRows(bars.map { it.meter.numerator }, maxWidth.value, beatWidth.value)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { i ->
                        val bar = bars[i]
                        val local = bar.applyTo(settings)
                        val states = local.effectiveBeats
                        val accent = MaterialTheme.colorScheme.primary
                        Column(Modifier.weight(1f).border(1.dp, if (selected == i) accent else Edge,
                            RoundedCornerShape(10.dp)).background(Panel, RoundedCornerShape(10.dp))
                             .clickable(role = Role.Button, onClickLabel = stringResource(R.string.select_bar)) { select(i) }.padding(8.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1} · ${bar.meter.numerator}/${bar.meter.denominator}", Modifier.weight(1f), color = accent)
                                IconButton(onClick = { open(i) }, modifier = Modifier.size(48.dp)
                                    .semantics { contentDescription = context.getString(R.string.edit_bar_number, i + 1) }) {
                                    Text("⚙", color = Muted)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              if (local.subdivision != Subdivision.NONE) {
                                CompositionLocalProvider(LocalContentColor provides Muted) { NoteGlyph(if (local.subdivision == Subdivision.EIGHTH) .5 else .25) }
                                Spacer(Modifier.width(4.dp))
                              }
                              Text(stringResource(R.string.bar_subdivision, stringResource(when (local.subdivision) {
                                Subdivision.NONE -> R.string.none
                                Subdivision.EIGHTH -> R.string.eighths
                                Subdivision.SIXTEENTH -> R.string.sixteenths
                              })), style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                            val swingActive = local.swingEnabled && local.subdivision != Subdivision.NONE
                            val percent = remember(context.resources.configuration.locales[0]) {
                                NumberFormat.getPercentInstance(context.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              Text(if (swingActive) stringResource(R.string.bar_swing_on, percent.format(local.swing))
                                else stringResource(R.string.bar_swing_off), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f), color = if (swingActive) accent else Muted)
                              if (swingActive) CompositionLocalProvider(LocalContentColor provides accent) {
                                SwingGlyph(local.subdivision == Subdivision.SIXTEENTH,
                                    stringResource(if (local.subdivision == Subdivision.SIXTEENTH) R.string.swing_sixteenth_symbol else R.string.swing_eighth_symbol))
                              }
                            }
                            FlowRow(maxItemsInEachRow = Int.MAX_VALUE) {
                                states.forEachIndexed { beat, state ->
                                    val label = stringResource(when (state) { BeatState.NORMAL -> R.string.on; BeatState.ACCENT -> R.string.accented; BeatState.MUTED -> R.string.beat_off })
                                    val current = position?.patternBarIndex == i && position.beatIndex == beat
                                    Column(Modifier.width(beatWidth).heightIn(min = 60.dp)
                                        .background(if (current) accent.copy(alpha = .18f) else Color.Transparent, RoundedCornerShape(6.dp))
                                        .clickable(role = Role.Button) { val values = states.toMutableList()
                                            values[beat] = when (state) { BeatState.NORMAL -> BeatState.ACCENT; BeatState.ACCENT -> BeatState.MUTED; BeatState.MUTED -> BeatState.NORMAL }
                                            change(i, bar.copy(beats = values))
                                        }.semantics { contentDescription = context.getString(R.string.pattern_beat_state, i + 1, beat + 1, label) },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text((beat + 1).toString(), style = MaterialTheme.typography.labelSmall, color = Muted)
                                        Text(when(state) { BeatState.ACCENT -> "◆"; BeatState.NORMAL -> "●"; BeatState.MUTED -> "○" },
                                            color = if (state == BeatState.MUTED) Muted else accent, fontSize = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RhythmDialog(settings: Settings, previous: MutableMap<Int, BeatState>, update: (Settings) -> Unit, dismiss: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.rhythm)) }, text = {
        Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow(listOf(stringResource(R.string.meter_pulse), stringResource(R.string.beats)), section) { section = it }
            if (section == 0) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                MeterControls(settings, update = update)
            } else BeatControls(settings, previous, update)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) } })
}

@Composable
private fun BeatControls(settings: Settings, previous: MutableMap<Int, BeatState>, update: (Settings) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(settings.effectiveBeats.withIndex().toList(), key = { it.index }) { (index, state) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(R.string.beat_position, index + 1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state != BeatState.MUTED, { enabled ->
                        val beats = settings.effectiveBeats.toMutableList()
                        if (!enabled) previous[index] = state
                        beats[index] = if (enabled) previous[index] ?: BeatState.NORMAL else BeatState.MUTED
                        update(settings.copy(beats = beats))
                    }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.beat_audible, index + 1) })
                    Text(stringResource(if (state == BeatState.MUTED) R.string.muted else R.string.on), Modifier.weight(1f))
                    FilterChip(state == BeatState.ACCENT, {
                        val beats = settings.effectiveBeats.toMutableList(); beats[index] = if (state == BeatState.ACCENT) BeatState.NORMAL else BeatState.ACCENT
                        update(settings.copy(beats = beats))
                    }, enabled = state != BeatState.MUTED, label = { Text(stringResource(R.string.accented)) }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.beat_accented, index + 1) })
                }
            }
        }
    }
}
