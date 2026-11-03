package dev.pulsarconnect.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BikeScannerTest {

    @Test
    fun `scan deduplicates by address and sorts by strongest signal`() = runTest {
        val scanner = BikeScanner(
            BleScanSource {
                flow {
                    emit(testDevice("AA", rssi = -70, name = "Pulsar"))
                    emit(testDevice("BB", rssi = -50, name = "Other"))
                    emit(testDevice("AA", rssi = -40, name = "Pulsar"))
                    emit(testDevice("CC", rssi = -60, name = null))
                }
            },
        )

        val emissions = scanner.scan().toList()

        assertEquals(4, emissions.size)
        assertEquals(listOf("AA", "BB", "CC"), emissions.last().map { it.id })
        assertEquals(-40, emissions.last().first { it.id == "AA" }.rssi)
        assertEquals("Pulsar", emissions.last().first { it.id == "AA" }.name)
    }

    @Test
    fun `scan stops at the timeout and keeps devices found so far`() = runTest {
        val scanner = BikeScanner(
            BleScanSource {
                flow {
                    emit(testDevice("AA", rssi = -40))
                    delay(60_000)
                    emit(testDevice("BB", rssi = -30))
                }
            },
        )

        val emissions = scanner.scan(timeoutMillis = 1_000).toList()

        assertEquals(1, emissions.size)
        assertEquals(listOf("AA"), emissions.single().map { it.id })
    }

    @Test
    fun `cancelling the scan stops the source`() = runTest {
        var cancelled = false
        val scanner = BikeScanner(
            BleScanSource {
                flow {
                    try {
                        emit(testDevice("AA", rssi = -40))
                        emit(testDevice("BB", rssi = -30))
                        delay(60_000)
                    } finally {
                        cancelled = true
                    }
                }
            },
        )

        val first = scanner.scan().first()

        assertEquals(listOf("AA"), first.map { it.id })
        assertTrue(cancelled)
    }

    @Test
    fun `scan timeout does not cancel downstream processing`() = runTest {
        var sourceStopped = false
        var processed = false
        val scanner = BikeScanner(BleScanSource {
            flow {
                try {
                    emit(testDevice("AA", rssi = -40))
                    delay(60_000)
                } finally {
                    sourceStopped = true
                }
            }
        })

        scanner.scan(timeoutMillis = 1_000).collect {
            delay(2_000)
            processed = true
        }

        assertTrue(processed, "The scan deadline must not abort the collector")
        assertTrue(sourceStopped)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `empty scan emits nothing`() = runTest {
        val scanner = BikeScanner(BleScanSource { flow { } })
        assertTrue(scanner.scan().toList().isEmpty())
    }
}
