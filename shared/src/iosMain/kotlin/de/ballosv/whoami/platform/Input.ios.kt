package de.ballosv.whoami.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import de.ballosv.whoami.ui.showToast
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.NSTimer
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.UIKit.UIImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Spracheingabe mit der Spracherkennung von iOS. Es wird zugehört, bis nach dem Sprechen eine
 * kurze Pause entsteht (oder höchstens [MAX_SECONDS] Sekunden), dann zählt das Erkannte.
 */
private class SpeechListener {
    private var engine: AVAudioEngine? = null
    private var task: SFSpeechRecognitionTask? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var silence: NSTimer? = null
    private var limit: NSTimer? = null
    private var text = ""
    private var onDone: ((String) -> Unit)? = null

    fun start(onResult: (String) -> Unit) {
        if (engine != null) return
        SFSpeechRecognizer.requestAuthorization { status ->
            dispatch_async(dispatch_get_main_queue()) {
                if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    begin(onResult)
                } else {
                    showToast("Spracheingabe ist in den Einstellungen nicht erlaubt")
                }
            }
        }
    }

    private fun begin(onResult: (String) -> Unit) {
        val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = "de-DE"))
        if (!recognizer.isAvailable()) {
            showToast("Spracheingabe nicht verfügbar")
            return
        }
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeMeasurement, options = AVAudioSessionCategoryOptionDuckOthers, error = null)
        session.setActive(true, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)

        val req = SFSpeechAudioBufferRecognitionRequest().apply { shouldReportPartialResults = true }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        input.installTapOnBus(0u, bufferSize = 1024u, format = input.outputFormatForBus(0u)) { buffer, _ ->
            if (buffer != null) req.appendAudioPCMBuffer(buffer)
        }
        eng.prepare()
        if (!eng.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            showToast("Mikrofon nicht verfügbar")
            return
        }
        engine = eng
        request = req
        text = ""
        onDone = onResult
        showToast("Sprich jetzt …")
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            dispatch_async(dispatch_get_main_queue()) {
                if (result != null) {
                    text = result.bestTranscription.formattedString
                    if (result.isFinal()) finish() else restartSilenceTimer()
                } else if (error != null) {
                    finish()
                }
            }
        }
        limit = NSTimer.scheduledTimerWithTimeInterval(MAX_SECONDS, repeats = false) { _ -> finish() }
    }

    private fun restartSilenceTimer() {
        silence?.invalidate()
        silence = NSTimer.scheduledTimerWithTimeInterval(1.2, repeats = false) { _ -> finish() }
    }

    fun finish() {
        val eng = engine ?: return
        engine = null
        silence?.invalidate()
        limit?.invalidate()
        eng.stop()
        eng.inputNode.removeTapOnBus(0u)
        request?.endAudio()
        task?.cancel()
        task = null
        request = null
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
        val result = text
        onDone?.invoke(result)
        onDone = null
        if (result.isBlank()) showToast("Nichts verstanden")
    }

    private companion object {
        const val MAX_SECONDS = 7.0
    }
}

@Composable
actual fun rememberSpeechInput(onResult: (String) -> Unit): (() -> Unit)? {
    val callback by rememberUpdatedState(onResult)
    val listener = remember { SpeechListener() }
    DisposableEffect(listener) { onDispose { listener.finish() } }
    return { listener.start { callback(it) } }
}

/** Fotoauswahl über den System-Picker; die Bilder werden gleich quadratisch zugeschnitten. */
private class PickerDelegate(private val onPicked: (List<ByteArray>) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) return
        val collected = arrayOfNulls<ByteArray>(results.size)
        var pending = results.size
        results.forEachIndexed { index, result ->
            result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                val jpeg = data?.toByteArray()?.let { bytes ->
                    UIImage.imageWithData(bytes.toNSData())?.let { ImageOps.squareJpegFrom(bytes) }
                }
                dispatch_async(dispatch_get_main_queue()) {
                    collected[index] = jpeg
                    if (--pending == 0) onPicked(collected.filterNotNull())
                }
            }
        }
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): (max: Int) -> Unit {
    val callback by rememberUpdatedState(onPicked)
    // Der Picker hält seinen Delegate nur schwach – deshalb hier festhalten.
    val delegate = remember { PickerDelegate { callback(it) } }
    return { max ->
        val config = PHPickerConfiguration().apply {
            selectionLimit = max.coerceAtLeast(1).toLong()
            filter = PHPickerFilter.imagesFilter
        }
        val picker = PHPickerViewController(configuration = config)
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}
