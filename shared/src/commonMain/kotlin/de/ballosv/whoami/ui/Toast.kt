package de.ballosv.whoami.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Kurzer Hinweis am unteren Rand (wie ein Android-Toast, aber auf beiden Plattformen gleich). */
private val toast = MutableStateFlow<Pair<Long, String>?>(null)
private var toastCounter = 0L

fun showToast(text: String) {
    toast.value = ++toastCounter to text
}

@Composable
fun ToastHost() {
    val current by toast.collectAsStateCompat()
    var visible by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        val (_, text) = current ?: return@LaunchedEffect
        visible = text
        delay(3_500)
        visible = null
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Text(visible ?: "", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), textAlign = TextAlign.Center)
            }
        }
    }
}
