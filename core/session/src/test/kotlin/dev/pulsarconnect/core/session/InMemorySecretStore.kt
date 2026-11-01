package dev.pulsarconnect.core.session

class InMemorySecretStore(initial: Map<String, String> = emptyMap()) : SecretStore {

    private val values = initial.toMutableMap()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
