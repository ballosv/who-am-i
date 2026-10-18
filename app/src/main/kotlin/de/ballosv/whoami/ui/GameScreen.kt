package de.ballosv.whoami.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ballosv.whoami.core.EventType
import de.ballosv.whoami.core.GameEvent
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.core.Phase
import de.ballosv.whoami.session.GameSession
import de.ballosv.whoami.ui.theme.Accent
import de.ballosv.whoami.ui.theme.Bad
import de.ballosv.whoami.ui.theme.Good
import kotlinx.coroutines.delay

@Composable
fun GameScreen(session: GameSession, snapshot: GameSnapshot) {
    val clock = rememberHostClock(session)
    val image = rememberSessionImage(session, snapshot.imageId)
    val myId = session.myId
    val tv = snapshot.tvMode
    val event = rememberEventBanner(snapshot, myId)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            GameHeader(snapshot, clock)
            if (!tv) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight)
                    Box(Modifier.size(side)) {
                        TileBoard(snapshot, clock, image, Modifier.fillMaxSize())
                        BoardOverlay(snapshot, clock, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(8.dp))
                RankingRow(snapshot, myId, Modifier.fillMaxWidth())
            } else {
                TvPlayerStatus(snapshot, myId, Modifier.weight(1f))
            }
            EventBanner(event)
            ActionArea(session, snapshot, clock, tv)
        }
        FullscreenOverlay(snapshot, clock)
    }
}

@Composable
private fun GameHeader(s: GameSnapshot, clock: State<Long>) {
    val points by remember(s) {
        derivedStateOf { if (s.phase == Phase.ANSWERING) s.buzzPoints else s.pointsAt(clock.value) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (s.roundCount > 0) "Person ${s.roundIndex + 1} / ${s.roundCount}" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.settings.mode == GameMode.BATTLE && s.ownerId != null) {
                Text("Bild von ${s.player(s.ownerId)?.name ?: "?"}", color = Accent, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            PointsDisplay(points)
            Text("mögliche Punkte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Hinweise direkt auf dem Bild: „Nächste Person …“ und die Auflösung am Rundenende. */
@Composable
private fun BoardOverlay(s: GameSnapshot, clock: State<Long>, modifier: Modifier) {
    val waiting by remember(s) { derivedStateOf { s.phase == Phase.REVEALING && clock.value < s.revealAnchor } }
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (waiting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nächste Person …", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
        }
        AnimatedVisibility(
            visible = s.phase == Phase.ROUND_RESULT,
            enter = slideInVertically { it } + fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.solution ?: "", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)
                    val e = s.lastEvent
                    Text(
                        when (e?.type) {
                            EventType.CORRECT -> "${s.player(e.playerId)?.name} +${e.points}"
                            EventType.ALL_PASSED -> "Alle haben gepasst"
                            else -> "Niemand hat es erraten"
                        },
                        color = if (e?.type == EventType.CORRECT) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** TV-Modus: Das Handy ist nur Buzzer; statt des Bildes stehen hier Platz und Punktestand. */
@Composable
private fun TvPlayerStatus(s: GameSnapshot, myId: String, modifier: Modifier) {
    val ranking = s.ranking
    val rank = ranking.indexOfFirst { it.id == myId } + 1
    val me = s.player(myId)
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Schau auf den Fernseher!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Platz $rank von ${ranking.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${me?.score ?: 0} Punkte", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Accent)
        if (s.phase == Phase.ROUND_RESULT) {
            Spacer(Modifier.height(16.dp))
            Text(s.solution ?: "", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ActionArea(session: GameSession, s: GameSnapshot, clock: State<Long>, tv: Boolean) {
    val myId = session.myId
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        when {
            s.phase == Phase.ANSWERING && s.buzzPlayerId == myId -> AnswerPanel(s, clock, onSubmit = session::answer)
            s.phase == Phase.ANSWERING -> Text(
                "${s.player(s.buzzPlayerId)?.name} antwortet …",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 40.dp),
            )
            s.ownerId == myId -> Text(
                "Dein Bild – die anderen raten!",
                style = MaterialTheme.typography.titleLarge,
                color = Accent,
                modifier = Modifier.padding(vertical = 40.dp),
            )
            else -> {
                val active by remember(s) { derivedStateOf { s.phase == Phase.REVEALING && clock.value >= s.revealAnchor } }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BuzzerButton(
                        enabled = active,
                        label = "BUZZ!",
                        size = if (tv) 220 else 140,
                        onBuzz = {
                            vibrate(context)
                            session.buzz()
                        },
                    )
                    PassButton(s, myId, enabled = active, onToggle = session::setPassed)
                }
            }
        }
    }
}

/** Passen: Haben alle ratenden Spieler gepasst, wird die Person aufgelöst. Passen lässt sich zurücknehmen. */
@Composable
private fun PassButton(s: GameSnapshot, myId: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val passed = s.hasPassed(myId)
    val guessers = s.guessers
    val count = guessers.count { s.hasPassed(it.id) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (passed) {
            Button(onClick = { onToggle(false) }, enabled = enabled) { Text("Passen zurücknehmen") }
        } else {
            OutlinedButton(onClick = { onToggle(true) }, enabled = enabled) { Text("Passen") }
        }
        if (guessers.size > 1 && count > 0) {
            Text(
                "$count von ${guessers.size} haben gepasst",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Countdown vor dem Start und Ladefortschritt – über allem anderen. */
@Composable
private fun FullscreenOverlay(s: GameSnapshot, clock: State<Long>) {
    val countdown by remember(s) {
        derivedStateOf {
            if (s.phase == Phase.COUNTDOWN) (((s.countdownEndsAt - clock.value) + 999) / 1000).toInt().coerceAtLeast(0) else 0
        }
    }
    if (s.phase == Phase.PREPARING) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 320.dp).padding(24.dp)) {
                Text(s.message ?: "Spiel wird vorbereitet …", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { s.preparingProgress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (s.phase == Phase.COUNTDOWN && countdown > 0) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = countdown,
                transitionSpec = { (scaleIn(initialScale = 2f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut()) },
                label = "countdown",
            ) { value ->
                Text("$value", fontSize = 160.sp, fontWeight = FontWeight.Black, color = Accent)
            }
        }
    }
}

private data class BannerInfo(val text: String, val color: Color, val key: Long)

@Composable
private fun rememberEventBanner(s: GameSnapshot, myId: String): BannerInfo? {
    var banner by remember { mutableStateOf<BannerInfo?>(null) }
    val context = LocalContext.current
    val event: GameEvent? = s.lastEvent
    LaunchedEffect(event?.id) {
        val e = event ?: return@LaunchedEffect
        val who = if (e.playerId == myId) "Du" else s.player(e.playerId)?.name ?: "?"
        banner = when (e.type) {
            EventType.WRONG -> BannerInfo("$who: „${e.answer}“ ist falsch  ${e.points}", Bad, e.id)
            EventType.TIMEOUT -> BannerInfo("$who: Zeit abgelaufen  ${e.points}", Bad, e.id)
            EventType.CORRECT -> BannerInfo("$who: richtig!  +${e.points}", Good, e.id)
            EventType.UNSOLVED, EventType.ALL_PASSED -> null
        }
        if (e.playerId == myId && e.type != EventType.CORRECT) vibrate(context, long = true)
        delay(2_800)
        banner = null
    }
    return banner
}

@Composable
private fun EventBanner(info: BannerInfo?) {
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = info,
            transitionSpec = { (slideInVertically(tween(250)) { it } + fadeIn()) togetherWith fadeOut() },
            label = "banner",
        ) { b ->
            if (b != null) Text(b.text, color = b.color, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

private fun vibrate(context: Context, long: Boolean = false) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(if (long) 250 else 40, VibrationEffect.DEFAULT_AMPLITUDE)) }
}
