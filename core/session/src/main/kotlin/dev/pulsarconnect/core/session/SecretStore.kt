package dev.pulsarconnect.core.session

/**
 * A tiny key/value port for sensitive bytes.
 *
 * Production uses the Android Keystore (see the app module). Tests use an
 * in-memory implementation, which keeps this module plain JVM code.
 */
interface SecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
}
