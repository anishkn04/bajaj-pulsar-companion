package dev.pulsarconnect.core.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable bike-link intent. These flags are global rather than per-VIN because they describe
 * how the rider wants the phone to behave, not which bike it talks to.
 *
 * [armedVin] is a lookup key only: it lets the background service find the selected bike
 * without decrypting the session while the phone is booting.
 *
 * Call mirroring is opt-in and off by default because the cluster would receive a contact's
 * display name. Missed calls carry no personal data but are still opt-in.
 */
data class BikeLinkSettings(
    val mirroring: Boolean = true,
    val keepInBackground: Boolean = true,
    val startOnBoot: Boolean = true,
    val mirrorCallerName: Boolean = false,
    val mirrorMissedCalls: Boolean = false,
    val armedVin: String? = null,
) {
    /** Mirroring only survives leaving the app when background mode is also on. */
    val runsInBackground: Boolean get() = mirroring && keepInBackground

    /** Boot auto-start is opt-in and only meaningful when a background link is armed. */
    val resumesAfterBoot: Boolean get() = startOnBoot && runsInBackground && armedVin != null

    /** True while at least one call feature is wanted. */
    val mirrorsAnyCall: Boolean get() = mirrorCallerName || mirrorMissedCalls
}

interface BikeLinkPreferences {
    val settings: StateFlow<BikeLinkSettings>

    fun setMirroring(enabled: Boolean)

    fun setKeepInBackground(enabled: Boolean)

    fun setStartOnBoot(enabled: Boolean)

    fun setMirrorCallerName(enabled: Boolean)

    fun setMirrorMissedCalls(enabled: Boolean)

    fun setArmedVin(vin: String?)
}

/** Process-local implementation used by tests and previews. */
class InMemoryBikeLinkPreferences(
    initial: BikeLinkSettings = BikeLinkSettings(),
) : BikeLinkPreferences {
    private val mutable = MutableStateFlow(initial)
    override val settings: StateFlow<BikeLinkSettings> = mutable.asStateFlow()

    override fun setMirroring(enabled: Boolean) {
        mutable.value = mutable.value.copy(mirroring = enabled)
    }

    override fun setKeepInBackground(enabled: Boolean) {
        mutable.value = mutable.value.copy(keepInBackground = enabled)
    }

    override fun setStartOnBoot(enabled: Boolean) {
        mutable.value = mutable.value.copy(startOnBoot = enabled)
    }

    override fun setMirrorCallerName(enabled: Boolean) {
        mutable.value = mutable.value.copy(mirrorCallerName = enabled)
    }

    override fun setMirrorMissedCalls(enabled: Boolean) {
        mutable.value = mutable.value.copy(mirrorMissedCalls = enabled)
    }

    override fun setArmedVin(vin: String?) {
        mutable.value = mutable.value.copy(armedVin = vin)
    }
}
