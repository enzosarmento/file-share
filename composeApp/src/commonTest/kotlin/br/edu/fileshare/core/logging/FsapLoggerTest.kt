package br.edu.fileshare.core.logging

import br.edu.fileshare.core.di.DefaultAppContainer
import br.edu.fileshare.core.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FixedClock(
    private val fixedMillis: Long = 1_762_992_000_000L,
    private val fixedIso: String = "2026-11-12T22:00:00Z",
) : Clock {
    override fun epochMillis(): Long = fixedMillis
    override fun nowIsoUtc(): String = fixedIso
}

class FsapLoggerTest {

    @Test
    fun formatStructuredLogIncludesTimestampComponentContextAndMessage() {
        val clock = FixedClock()
        val logger = InMemoryFsapLogger(clock = clock)

        logger.info(
            component = "TransferCoordinator",
            message = "Chunk received successfully",
            context = LogContext(
                deviceId = "dev-001",
                transferId = "tr-999",
                peerId = "peer-002",
            ),
        )

        val records = logger.snapshot()
        assertEquals(1, records.size)
        val formatted = logger.formattedSnapshot().single()
        assertTrue(formatted.contains("2026-11-12T22:00:00Z"))
        assertTrue(formatted.contains("[INFO]"))
        assertTrue(formatted.contains("[TransferCoordinator]"))
        assertTrue(formatted.contains("deviceId=dev-001"))
        assertTrue(formatted.contains("transferId=tr-999"))
        assertTrue(formatted.contains("peerId=peer-002"))
        assertTrue(formatted.contains("Chunk received successfully"))
    }

    @Test
    fun minLevelFiltersLowerSeverityAndCapturesStateTransition() {
        val logger = InMemoryFsapLogger(clock = FixedClock(), minLevel = LogLevel.INFO)
        logger.debug("Discovery", "Filtered debug message")
        logger.logStateTransition(
            component = "TransferFSM",
            entityId = "tr-1",
            fromState = "CREATED",
            event = "EV-06",
            toState = "PENDING_APPROVAL",
            context = LogContext(transferId = "tr-1"),
        )
        logger.error("Network", "Socket closed", throwable = IllegalStateException("reset"))

        val records = logger.snapshot()
        assertEquals(2, records.size)
        assertEquals(LogLevel.INFO, records[0].level)
        assertTrue(records[0].message.contains("CREATED --(EV-06)--> PENDING_APPROVAL"))
        assertEquals(LogLevel.ERROR, records[1].level)
        assertTrue(FsapLogFormatter.format(records[1]).contains("cause=IllegalStateException: reset"))
    }

    @Test
    fun appContainerWiresInjectedLoggerAndClock() {
        val clock = FixedClock()
        val logger = InMemoryFsapLogger(clock = clock)
        val container = DefaultAppContainer(logger = logger, clock = clock)

        container.logger.warn("Bootstrap", "Test warning")
        assertEquals(1, logger.snapshot().size)
        assertEquals(1_762_992_000_000L, container.clock.epochMillis())
    }
}

