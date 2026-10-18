package de.ballosv.whoami.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.core.Phase
import de.ballosv.whoami.core.PlayerInfo
import de.ballosv.whoami.session.GameSession
import de.ballosv.whoami.ui.theme.Accent
import de.ballosv.whoami.ui.theme.Bad
import de.ballosv.whoami.ui.theme.Buzzer
import de.ballosv.whoami.ui.theme.TileA
import de.ballosv.whoami.ui.theme.TileB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-Uhrzeit, jedes Frame aktualisiert. Nur in Zeichen-/Ableitungscode lesen, damit nicht alles neu komponiert. */
@Composable
fun rememberHostClock(session: GameSession): State<Long> = produceState(session.hostNow(), session) {
    while (true) {
        withFrameMillis { }
        value = session.hostNow()
    }
}

@Composable
fun rememberSessionImage(session: GameSession, imageId: String?): ImageBitmap? {
    val ids by session.images.ids.collectAsStateCompat()
    val available = imageId != null && imageId in ids
    val bitmap by produceState<ImageBitmap?>(null, imageId, available) {
        value = if (imageId == null || !available) null
        else withContext(Dispatchers.Default) { session.images.bitmap(imageId)?.asImageBitmap() }
    }
    return bitmap
}

/** Das Bild mit den noch verdeckenden Kacheln; die zuletzt aufgedeckte Kachel löst sich sichtbar auf. */
@Composable
fun TileBoard(snapshot: GameSnapshot, clock: State<Long>, image: ImageBitmap?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.clip(shape).background(Color.Black)) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val brush = remember { Brush.linearGradient(listOf(TileA, TileB)) }
        Canvas(Modifier.fillMaxSize()) {
            val s = snapshot
            val t = clock.value
            val g = s.settings.gridSize
            val total = g * g
            val revealed = s.revealedAt(t)
            val fade = if (s.phase == Phase.REVEALING) {
                s.fadeProgressAt(t, minOf(450L, s.settings.revealIntervalMs))
            } else 1f
            val alpha = FloatArray(total) { 1f }
            val order = s.tileOrder
            for (i in 0 until minOf(revealed, order.size)) {
                val tile = order[i]
                if (tile in 0 until total) alpha[tile] = if (i == revealed - 1) 1f - fade else 0f
            }
            val cell = size.width / g
            val gap = 1.5.dp.toPx()
            for (tile in 0 until total) {
                val a = alpha[tile]
                if (a <= 0f) continue
                val x = (tile % g) * cell
                val y = (tile / g) * cell
                // Auflösende Kachel schrumpft beim Ausblenden etwas.
                val shrink = if (a < 1f) (1f - a) * cell * 0.2f else 0f
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x + gap + shrink, y + gap + shrink),
                    size = Size(cell - 2 * gap - 2 * shrink, cell - 2 * gap - 2 * shrink),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    alpha = a,
                )
            }
        }
    }
}

/** Aktueller Punktewert; jede Verringerung wird mit einer kleinen Animation sichtbar. */
@Composable
fun PointsDisplay(points: Int, modifier: Modifier = Modifier, fontSize: Int = 44) {
    var previous by remember { mutableIntStateOf(points) }
    var drop by remember { mutableIntStateOf(0) }
    var dropKey by remember { mutableIntStateOf(0) }
    val pulse = remember { Animatable(1f) }
    val dropAnim = remember { Animatable(1f) }
    LaunchedEffect(points) {
        if (points < previous) {
            drop = points - previous
            dropKey++
            launch {
                dropAnim.snapTo(0f)
                dropAnim.animateTo(1f, tween(900))
            }
            pulse.snapTo(1.25f)
            pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
        previous = points
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = points,
            transitionSpec = {
                if (targetState < initialState) {
                    (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = "points",
        ) { value ->
            Text(
                "$value",
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Black,
                color = Accent,
                modifier = Modifier.scale(pulse.value),
            )
        }
        if (dropKey > 0 && dropAnim.value < 1f) {
            Text(
                "$drop",
                color = Bad,
                fontWeight = FontWeight.Bold,
                fontSize = (fontSize / 2.2f).sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        translationY = -dropAnim.value * 40.dp.toPx()
                        translationX = 36.dp.toPx()
                        alpha = 1f - dropAnim.value
                    },
            )
        }
    }
}

/** Rangliste, immer nach Punkten sortiert; Positionswechsel werden animiert. */
@Composable
fun RankingRow(snapshot: GameSnapshot, myId: String, modifier: Modifier = Modifier) {
    val ranking = snapshot.ranking
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(ranking, key = { it.id }) { player ->
            PlayerChip(
                rank = ranking.indexOf(player) + 1,
                player = player,
                me = player.id == myId,
                buzzing = player.id == snapshot.buzzPlayerId,
                owner = player.id == snapshot.ownerId,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
fun PlayerChip(rank: Int, player: PlayerInfo, me: Boolean, buzzing: Boolean, owner: Boolean, modifier: Modifier = Modifier) {
    val border = when {
        buzzing -> Accent
        me -> MaterialTheme.colorScheme.secondary
        else -> Color.Transparent
    }
    Surface(
        modifier = modifier.border(2.dp, border, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$rank.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    player.name + if (!player.connected) " (offline)" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(IntrinsicNameWidth),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (owner) Text("zeigt Bild", color = Accent, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(6.dp))
            Text("${player.score}", fontWeight = FontWeight.Black)
        }
    }
}

private val IntrinsicNameWidth = 84.dp

@Composable
fun BuzzerButton(enabled: Boolean, label: String, onBuzz: () -> Unit, modifier: Modifier = Modifier, size: Int = 150) {
    val press = remember { Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val currentOnBuzz by rememberUpdatedState(onBuzz)
    val color = if (enabled) Buzzer else Buzzer.copy(alpha = 0.3f)
    Box(
        modifier
            .size(size.dp)
            .scale(press.value)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(color, color.copy(alpha = if (enabled) 0.75f else 0.2f))))
            .border(6.dp, Color.White.copy(alpha = if (enabled) 0.8f else 0.2f), CircleShape)
            .then(
                if (enabled) Modifier.pointerTapImmediate {
                    currentOnBuzz()
                    scope.launch {
                        press.snapTo(0.88f)
                        press.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
            fontWeight = FontWeight.Black,
            fontSize = (size / 6.5f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Antwortfeld mit Restzeit-Balken, Tastatur- und Spracheingabe. */
@Composable
fun AnswerPanel(snapshot: GameSnapshot, clock: State<Long>, onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(snapshot.answerDeadline) { mutableStateOf("") }
    var sent by remember(snapshot.answerDeadline) { mutableStateOf(false) }
    val submit by rememberUpdatedState { value: String ->
        if (!sent && value.isNotBlank()) {
            sent = true
            onSubmit(value.trim())
        }
    }
    val context = LocalContext.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            text = spoken
            submit(spoken)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val total = snapshot.settings.answerTimeSeconds * 1000f

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Wer ist das?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { ((snapshot.answerDeadline - clock.value) / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Accent,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                enabled = !sent,
                placeholder = { Text("Name eintippen …") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(text) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            FilledIconButton(
                enabled = !sent,
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sag den Namen")
                    try {
                        speech.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, "Spracheingabe nicht verfügbar", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(52.dp),
            ) { Icon(Icons.Default.Mic, contentDescription = "Name sagen") }
            FilledIconButton(enabled = !sent && text.isNotBlank(), onClick = { submit(text) }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Antwort senden")
            }
        }
    }
}
