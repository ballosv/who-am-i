package de.ballosv.whoami.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.session.GameSession
import de.ballosv.whoami.session.HostSession
import de.ballosv.whoami.ui.theme.Accent

private val medals = listOf(Color(0xFFFFD54F), Color(0xFFCFD8DC), Color(0xFFD7A26C))

@Composable
fun FinishedScreen(session: GameSession, snapshot: GameSnapshot, onMenu: () -> Unit) {
    val ranking = snapshot.ranking
    val single = snapshot.settings.mode == GameMode.SINGLE
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = Accent, modifier = Modifier.size(72.dp))
        Text(
            if (single) "${ranking.firstOrNull()?.score ?: 0} Punkte" else "${ranking.firstOrNull()?.name} gewinnt!",
            fontSize = 34.sp, fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(24.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ranking, key = { _, p -> p.id }) { index, p ->
                SectionCard(null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${index + 1}.",
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                            color = medals.getOrElse(index) { MaterialTheme.colorScheme.onSurfaceVariant },
                            modifier = Modifier.width(44.dp),
                        )
                        Text(p.name + if (p.id == session.myId) " (du)" else "", Modifier.weight(1f), fontSize = 18.sp)
                        Text("${p.score}", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                }
            }
        }
        if (session is HostSession) {
            Button(
                onClick = {
                    session.playAgain()
                    if (single) session.startGame()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Nochmal spielen", fontSize = 18.sp) }
        } else {
            Text("Der Host kann eine neue Runde starten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onMenu, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Hauptmenü", fontSize = 18.sp)
        }
    }
}
