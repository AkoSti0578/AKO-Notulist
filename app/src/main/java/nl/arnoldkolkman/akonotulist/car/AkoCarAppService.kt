package nl.arnoldkolkman.akonotulist.car

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.CarAppService
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import nl.arnoldkolkman.akonotulist.appContainer
import nl.arnoldkolkman.akonotulist.transcribe.AppEvent
import java.util.Locale

/**
 * Android Auto entry point (Car App Library, category IOT).
 *
 * The app is sideloaded for personal use and never published, so we accept every host. A Play
 * Store build would use HostValidator.Builder with the allow-list of trusted host signatures.
 */
class AkoCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session = AkoCarSession()
}

class AkoCarSession : Session() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreateScreen(intent: Intent): Screen {
        val c = carContext.appContainer
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                tts = TextToSpeech(carContext) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) tts?.language = Locale.forLanguageTag("nl-NL")
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                tts?.shutdown()
                tts = null
            }
        })
        lifecycleScope.launch {
            c.events.collect { e ->
                when (e) {
                    is AppEvent.TranscriptionDone -> {
                        CarToast.makeText(carContext, "Transcriptie klaar: ${e.title}", CarToast.LENGTH_LONG).show()
                        if (c.settings.current().carTts) speak("Transcriptie klaar")
                    }
                    is AppEvent.TranscriptionFailed ->
                        CarToast.makeText(carContext, "Transcriptie mislukt: ${e.title}", CarToast.LENGTH_LONG).show()
                }
            }
        }
        return ControlScreen(carContext)
    }

    /** Short spoken notice with transient audio focus (ducks music, resumes afterwards). */
    private fun speak(text: String) {
        val engine = tts ?: return
        if (!ttsReady) return
        val am = carContext.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(attrs)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { am.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { am.abandonAudioFocusRequest(focus) }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ako_done")
    }
}
