package dev.pulsarconnect.core.ble

/** Privacy-safe idle readings only; no call state, caller identity, or text is accepted. */
data class PhoneStatus(
    val batteryPercent: Int?,
    val signalLevel: Int?,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) {
            "Battery percent must be unavailable or in 0..100"
        }
        require(signalLevel == null || signalLevel in 0..4) {
            "Signal level must be unavailable or in 0..4"
        }
    }
}
