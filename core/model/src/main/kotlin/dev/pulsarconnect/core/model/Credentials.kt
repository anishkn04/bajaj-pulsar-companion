package dev.pulsarconnect.core.model

/**
 * The long-lived pair a user is provisioned with by their dealer.
 *
 * These are the only values the user should ever have to type. They are stored
 * encrypted on-device and reused to re-authenticate silently.
 */
data class Credentials(
    val vin: Vin,
    val accessCode: String,
) {
    init {
        require(accessCode.isNotBlank()) { "access code must not be blank" }
    }
}
