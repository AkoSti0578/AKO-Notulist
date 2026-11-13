package nl.arnoldkolkman.akonotulist.recording

/** User preference for the recording microphone. */
enum class MicPreference(val key: String, val label: String) {
    AUTO("auto", "Automatisch: Bluetooth als die verbonden is, anders de telefoon"),
    PHONE("phone", "Altijd de microfoon van de telefoon");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** A microphone Android offers, reduced to what the choice needs (pure, unit tested). */
data class MicCandidate(val id: Int, val kind: Kind, val name: String) {
    enum class Kind { BLUETOOTH_SCO, BLE_HEADSET, USB, WIRED, BUILTIN, OTHER }

    val isBluetooth get() = kind == Kind.BLUETOOTH_SCO || kind == Kind.BLE_HEADSET
}

object MicSelector {
    /**
     * The Bluetooth microphone to route to, or null for the phone's own microphone. LE Audio
     * headsets (better quality, no call-mode) are preferred over classic SCO headsets.
     * Wired and USB headsets need no routing: Android already uses them for the normal source.
     */
    fun choose(candidates: List<MicCandidate>, preference: MicPreference): MicCandidate? {
        if (preference == MicPreference.PHONE) return null
        return candidates.firstOrNull { it.kind == MicCandidate.Kind.BLE_HEADSET }
            ?: candidates.firstOrNull { it.kind == MicCandidate.Kind.BLUETOOTH_SCO }
    }

    /** Short label for the UI and the notification. */
    fun label(chosen: MicCandidate?, candidates: List<MicCandidate>): String = when {
        chosen != null -> "🎧 " + chosen.name.ifBlank { "Bluetooth-microfoon" }
        candidates.any { it.kind == MicCandidate.Kind.WIRED || it.kind == MicCandidate.Kind.USB } ->
            "🎙 " + (candidates.first { it.kind == MicCandidate.Kind.WIRED || it.kind == MicCandidate.Kind.USB }.name.ifBlank { "Headset" })
        else -> "📱 Telefoonmicrofoon"
    }
}
