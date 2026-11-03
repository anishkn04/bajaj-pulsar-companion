package dev.pulsarconnect.core.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Converts raw scan events into stable UI snapshots.
 *
 * Deduplication is by platform address, which is the only identity BLE gives us before
 * the cluster protocol is known. The latest advertisement wins, including its RSSI.
 */
class BikeScanner(
    private val source: BleScanSource,
) : BleScanner {
    override fun scan(timeoutMillis: Long): Flow<List<DiscoveredDevice>> {
        require(timeoutMillis > 0) { "Scan timeout must be positive" }
        // Keep the scan deadline out of the downstream collector's coroutine.
        return channelFlow {
            val found = LinkedHashMap<String, DiscoveredDevice>()
            withTimeoutOrNull(timeoutMillis) {
                source.scan().collect { device ->
                    found[device.id] = device
                    send(
                        found.values.sortedWith(
                            compareByDescending<DiscoveredDevice> { it.rssi }
                                .thenBy { it.name ?: "\uFFFF" }
                                .thenBy { it.id },
                        ),
                    )
                }
            }
        }.buffer(Channel.RENDEZVOUS)
    }
}
