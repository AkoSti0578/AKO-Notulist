package nl.arnoldkolkman.akonotulist.recording

import nl.arnoldkolkman.akonotulist.recording.MicCandidate.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicSelectorTest {
    private val builtin = MicCandidate(1, Kind.BUILTIN, "Pixel")
    private val buds = MicCandidate(2, Kind.BLUETOOTH_SCO, "Galaxy Buds")
    private val leAudio = MicCandidate(3, Kind.BLE_HEADSET, "Pixel Buds Pro")
    private val wired = MicCandidate(4, Kind.WIRED, "Headset")

    @Test fun phoneOnlyWithoutBluetooth() = assertNull(MicSelector.choose(listOf(builtin), MicPreference.AUTO))

    @Test fun autoPicksBluetoothHeadset() = assertEquals(buds, MicSelector.choose(listOf(builtin, buds), MicPreference.AUTO))

    @Test fun leAudioPreferredOverClassicSco() =
        assertEquals(leAudio, MicSelector.choose(listOf(builtin, buds, leAudio), MicPreference.AUTO))

    @Test fun phonePreferenceIgnoresBluetooth() = assertNull(MicSelector.choose(listOf(builtin, buds), MicPreference.PHONE))

    @Test fun wiredHeadsetNeedsNoRouting() = assertNull(MicSelector.choose(listOf(builtin, wired), MicPreference.AUTO))

    @Test fun labels() {
        assertEquals("🎧 Galaxy Buds", MicSelector.label(buds, listOf(builtin, buds)))
        assertEquals("🎧 Bluetooth-microfoon", MicSelector.label(buds.copy(name = ""), listOf(builtin)))
        assertEquals("🎙 Headset", MicSelector.label(null, listOf(builtin, wired)))
        assertEquals("📱 Telefoonmicrofoon", MicSelector.label(null, listOf(builtin)))
    }

    @Test fun preferenceKeys() {
        assertEquals(MicPreference.PHONE, MicPreference.of("phone"))
        assertEquals(MicPreference.AUTO, MicPreference.of(null))
        assertEquals(MicPreference.AUTO, MicPreference.of("onzin"))
    }
}
