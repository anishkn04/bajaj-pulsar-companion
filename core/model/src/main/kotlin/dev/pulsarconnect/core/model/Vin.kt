package dev.pulsarconnect.core.model

/**
 * A 17-character Vehicle Identification Number (chassis number).
 *
 * Bajaj vehicles use the standard ISO 3779 format. We normalise case and
 * surrounding whitespace because the official app does the same, and users
 * routinely paste VINs copied from documents.
 */
@JvmInline
value class Vin private constructor(val value: String) {

    companion object {
        const val LENGTH = 17
        private val ALLOWED = Regex("^[A-HJ-NPR-Z0-9]{17}$")

        /**
         * @throws IllegalArgumentException when [raw] cannot be a valid VIN.
         */
        fun of(raw: String): Vin {
            val normalised = raw.trim().uppercase()
            require(ALLOWED.matches(normalised)) {
                "VIN must be $LENGTH characters (A-Z, 0-9, excluding I, O and Q)"
            }
            return Vin(normalised)
        }
    }

    override fun toString(): String = value
}
