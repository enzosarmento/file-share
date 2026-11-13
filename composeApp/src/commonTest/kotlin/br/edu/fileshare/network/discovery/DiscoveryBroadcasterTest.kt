package br.edu.fileshare.network.discovery

import br.edu.fileshare.core.id.UuidGenerator
import br.edu.fileshare.core.time.Clock
import br.edu.fileshare.protocol.model.AnnouncePayload
import br.edu.fileshare.protocol.model.ByePayload
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.DiscoverPayload
import br.edu.fileshare.protocol.model.FsapJson
import br.edu.fileshare.protocol.model.FsapMessageType
import br.edu.fileshare.protocol.model.PlatformType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryBroadcasterTest {

    private class SteppingClock(private var nowMs: Long = 1_763_000_000_000L) : Clock {
        override fun epochMillis(): Long = nowMs
        override fun nowIsoUtc(): String = "2026-11-13T01:00:00Z"
    }

    private class SequentialUuidGenerator : UuidGenerator {
        private var seq = 1
        override fun nextUuidV4(): String {
            val suffix = (seq++).toString().padStart(12, '0')
            return "11111111-1111-4111-8111-$suffix"
        }
    }

    private val localIdentity = DeviceIdentity(
        deviceId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        alias = "Ubuntu-Lab",
        deviceType = DeviceType.DESKTOP,
        platform = PlatformType.LINUX,
        httpPort = 53317,
    )

    @Test
    fun `emite DISCOVER e ANNOUNCE iniciais, repete ANNOUNCE a cada 5s e emite BYE ao encerrar`() =
        runTest {
            val transport = FakeMulticastTransport()
            val broadcaster = DiscoveryBroadcaster(
                transport = transport,
                localIdentityProvider = { localIdentity },
                scope = backgroundScope,
                uuidGenerator = SequentialUuidGenerator(),
                clock = SteppingClock(),
                announceIntervalMs = DiscoveryDefaults.ANNOUNCE_INTERVAL_MS,
            )

            broadcaster.start()
            runCurrent()

            val initial = transport.snapshotSentStrings().map { FsapJson.decodeRawEnvelope(it) }
            assertEquals(2, initial.size)
            assertEquals(FsapMessageType.DISCOVER.name, initial[0].type)
            assertEquals(FsapMessageType.ANNOUNCE.name, initial[1].type)

            val discoverEnv = FsapJson.decodeEnvelope<DiscoverPayload>(transport.snapshotSentStrings()[0])
            assertEquals("Ubuntu-Lab", discoverEnv.payload.requesterAlias)
            assertEquals(53317, discoverEnv.payload.requesterHttpPort)

            val announceEnv = FsapJson.decodeEnvelope<AnnouncePayload>(transport.snapshotSentStrings()[1])
            assertEquals("Ubuntu-Lab", announceEnv.payload.alias)
            assertEquals(localIdentity.deviceId, announceEnv.senderId)

            // Avanca 5s -> deve emitir mais 1 ANNOUNCE
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(3, transport.sentPayloads.size)
            assertEquals(
                FsapMessageType.ANNOUNCE.name,
                FsapJson.decodeRawEnvelope(transport.snapshotSentStrings()[2]).type,
            )

            // Avanca mais 10s -> mais 2 ANNOUNCE (total 5)
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(5, transport.sentPayloads.size)

            // Refresh manual dispara DISCOVER imediato
            broadcaster.triggerDiscover()
            assertEquals(6, transport.sentPayloads.size)
            assertEquals(
                FsapMessageType.DISCOVER.name,
                FsapJson.decodeRawEnvelope(transport.snapshotSentStrings()[5]).type,
            )

            // Stop cancela o loop e envia BYE
            broadcaster.stop(reason = "SERVICE_STOPPED")
            assertEquals(7, transport.sentPayloads.size)
            val byeEnv = FsapJson.decodeEnvelope<ByePayload>(transport.snapshotSentStrings()[6])
            assertEquals(FsapMessageType.BYE.name, byeEnv.type)
            assertEquals("SERVICE_STOPPED", byeEnv.payload.reason)
            assertEquals(localIdentity.deviceId, byeEnv.senderId)

            // Avancar mais tempo nao gera novos pacotes
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(7, transport.sentPayloads.size)
        }
}

