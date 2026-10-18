package de.ballosv.whoami.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

/** Löst schon beim Herunterdrücken aus – beim Buzzer zählt jede Millisekunde. */
fun Modifier.pointerTapImmediate(onPress: () -> Unit): Modifier = pointerInput(Unit) {
    detectTapGestures(onPress = { onPress() })
}
