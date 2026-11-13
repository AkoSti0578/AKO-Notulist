package nl.arnoldkolkman.akonotulist.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes recording to a Bluetooth microphone (headset, earbuds, car kit) when one is connected.
 *
 * Android only records from a Bluetooth microphone after an app explicitly opens the
 * "communication" link to it (SCO, or LE Audio on newer phones) — like a calling app does.
 * Without that, MediaRecorder always uses the phone's own microphone.
 */
class MicRouter(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var routed = false
    private var usedLegacySco = false
    private var deviceCallback: AudioDeviceCallback? = null

    data class Route(
        val label: String,
        val bluetooth: Boolean,
        /** MediaRecorder audio source: VOICE_COMMUNICATION follows the communication device. */
        val audioSource: Int,
        val preferredDevice: AudioDeviceInfo?,
    )

    fun isRouted() = routed

    /** What Android currently offers as inputs. */
    fun candidates(): List<Pair<MicCandidate, AudioDeviceInfo>> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { d ->
            MicCandidate(d.id, kindOf(d.type), d.productName?.toString().orEmpty()) to d
        }

    /** Label of the microphone that would be used now (for the idle recorder card). */
    fun preview(preference: MicPreference): String {
        val list = candidates().map { it.first }
        return MicSelector.label(MicSelector.choose(list, preference), list)
    }

    suspend fun acquire(preference: MicPreference): Route {
        val list = candidates()
        val chosen = MicSelector.choose(list.map { it.first }, preference)
        val phone = Route(MicSelector.label(null, list.map { it.first }), false, MediaRecorder.AudioSource.MIC, null)
        if (chosen == null) return phone
        val input = list.first { it.first.id == chosen.id }.second

        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) routeModern(input) else routeLegacy()
        if (!ok) {
            am.mode = previousMode
            return phone.copy(label = phone.label + " (Bluetooth niet beschikbaar)")
        }
        routed = true
        return Route(MicSelector.label(chosen, list.map { it.first }), true, MediaRecorder.AudioSource.VOICE_COMMUNICATION, input)
    }

    private fun routeModern(input: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val wantedType = if (input.type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioDeviceInfo.TYPE_BLE_HEADSET else AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val comm = am.availableCommunicationDevices.filter { it.type == wantedType }
        val target = comm.firstOrNull { it.productName == input.productName } ?: comm.firstOrNull() ?: return false
        return runCatching { am.setCommunicationDevice(target) }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private suspend fun routeLegacy(): Boolean {
        if (!am.isBluetoothScoAvailableOffCall) return false
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return try {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            usedLegacySco = true
            val ok = withTimeoutOrNull(5_000) { connected.await() } == true
            if (!ok) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                usedLegacySco = false
            }
            ok
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Calls [onLost] when the routed Bluetooth microphone disconnects during the recording. */
    fun watch(onLost: () -> Unit) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (routed && removed.any { it.isSource && kindOf(it.type).let { k -> k == MicCandidate.Kind.BLUETOOTH_SCO || k == MicCandidate.Kind.BLE_HEADSET } }) {
                    onLost()
                }
            }
        }
        deviceCallback = cb
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    @Suppress("DEPRECATION")
    fun release() {
        deviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !usedLegacySco) {
            runCatching { am.clearCommunicationDevice() }
        }
        if (usedLegacySco) {
            runCatching { am.stopBluetoothSco() }
            am.isBluetoothScoOn = false
            usedLegacySco = false
        }
        am.mode = previousMode
    }

    companion object {
        fun kindOf(type: Int): MicCandidate.Kind = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicCandidate.Kind.BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_BLE_HEADSET -> MicCandidate.Kind.BLE_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> MicCandidate.Kind.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicCandidate.Kind.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicCandidate.Kind.BUILTIN
            else -> MicCandidate.Kind.OTHER
        }
    }
}
