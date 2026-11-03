package dev.pulsarconnect.core.ble

class InMemoryBikeDeviceStore : BikeDeviceStore {
    private val selected = mutableMapOf<String, BikeTarget>()
    private val autoConnect = mutableMapOf<String, Boolean>()

    override fun selected(vin: String): BikeTarget? = selected[vin]

    override fun select(target: BikeTarget) {
        selected[target.vin] = target
    }

    override fun clear(vin: String) {
        selected.remove(vin)
    }

    override fun autoConnect(vin: String): Boolean = autoConnect[vin] ?: false

    override fun setAutoConnect(vin: String, enabled: Boolean) {
        autoConnect[vin] = enabled
    }
}
