package br.edu.fileshare.protocol.statemachine

import br.edu.fileshare.core.logging.InMemoryFsapLogger
import br.edu.fileshare.core.logging.LogLevel
import br.edu.fileshare.protocol.error.FsapErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StateMachinesTest {

    @Test
    fun transferStateMachineFollowsHappyMultiFileLifecycleAndCallbacksOrder() = runTest {
        val logger = InMemoryFsapLogger()
        val lifecycleTrace = mutableListOf<String>()
        val fsm = TransferStateMachine(
            transferId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            logger = logger,
            onExit = { lifecycleTrace += "exit:${it.name}" },
            onEnter = { lifecycleTrace += "enter:${it.name}" },
        )

        assertEquals(TransferState.CREATED, fsm.state.value)
        fsm.transition(TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED)
        fsm.transition(TransferEvent.TRANSFER_ACCEPTED)
        fsm.transition(TransferEvent.STREAM_STARTED)
        fsm.transition(TransferEvent.CHUNK_TRANSFERRED)
        fsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
        fsm.transition(TransferEvent.FILE_VERIFIED_MORE_REMAIN)
        fsm.transition(TransferEvent.CHUNK_TRANSFERRED)
        fsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
        fsm.transition(TransferEvent.ALL_FILES_VERIFIED_AND_COMPLETED)

        assertEquals(TransferState.COMPLETED, fsm.state.value)
        assertTrue(fsm.state.value.isTerminal)
        assertEquals(listOf("exit:CREATED", "enter:PENDING_APPROVAL"), lifecycleTrace.take(2))
        assertTrue(logger.snapshot().all { it.level == LogLevel.INFO })
    }

    @Test
    fun exhaustiveTransferTransitionMatrixMatchesProtocolSpecificationTcFsap023() = runTest {
        for (sourceState in TransferState.entries) {
            for (event in TransferEvent.entries) {
                val expectedTarget = TransferStateMachine.TRANSITIONS[sourceState to event]
                val logger = InMemoryFsapLogger()
                val fsm = TransferStateMachine(transferId = "tr-matrix", logger = logger)
                walkToState(fsm, sourceState)

                if (expectedTarget != null) {
                    val result = fsm.transition(event)
                    assertEquals(expectedTarget, result)
                } else {
                    val ex = assertFailsWith<InvalidStateTransitionException>(
                        "Expected failure for ${sourceState.name} + ${event.name}"
                    ) {
                        fsm.transition(event)
                    }
                    assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION, ex.errorCode)
                    assertEquals(sourceState, fsm.currentState())
                    assertTrue(logger.snapshot().any { it.level == LogLevel.WARN })
                }
            }
        }
    }

    @Test
    fun exhaustiveLocalNodeTransitionMatrixIsDeterministic() = runTest {
        assertEquals(12, LocalNodeStateMachine.TRANSITIONS.size)
        for (sourceState in LocalNodeState.entries) {
            for (event in LocalNodeEvent.entries) {
                val expected = LocalNodeStateMachine.TRANSITIONS[sourceState to event]
                val fsm = LocalNodeStateMachine()
                walkLocalNodeTo(fsm, sourceState)

                if (expected != null) {
                    assertEquals(expected, fsm.transition(event))
                } else {
                    val ex = assertFailsWith<InvalidStateTransitionException> {
                        fsm.transition(event)
                    }
                    assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION, ex.errorCode)
                }
            }
        }
    }

    @Test
    fun exhaustivePeerConnectionTransitionMatrixIsDeterministic() = runTest {
        assertEquals(17, PeerConnectionStateMachine.TRANSITIONS.size)
        for (sourceState in PeerConnectionState.entries) {
            for (event in PeerConnectionEvent.entries) {
                val expected = PeerConnectionStateMachine.TRANSITIONS[sourceState to event]
                val fsm = PeerConnectionStateMachine(peerId = "peer-1", initialState = sourceState)

                if (expected != null) {
                    assertEquals(expected, fsm.transition(event))
                } else {
                    val ex = assertFailsWith<InvalidStateTransitionException> {
                        fsm.transition(event)
                    }
                    assertEquals(FsapErrorCode.INVALID_STATE_TRANSITION, ex.errorCode)
                    assertEquals(sourceState, fsm.currentState())
                }
            }
        }
    }

    @Test
    fun concurrentTransitionsAreSerializedByMutexWithoutCorruption() = runTest {
        val fsm = TransferStateMachine(transferId = "tr-concurrent")
        fsm.transition(TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED)

        // Exactly one terminal branch should win when racing accept vs reject
        val results = listOf(
            async { runCatching { fsm.transition(TransferEvent.TRANSFER_ACCEPTED) } },
            async { runCatching { fsm.transition(TransferEvent.TRANSFER_REJECTED) } },
        ).awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is InvalidStateTransitionException })
    }

    private suspend fun walkToState(fsm: TransferStateMachine, target: TransferState) {
        when (target) {
            TransferState.CREATED -> Unit
            TransferState.PENDING_APPROVAL -> fsm.transition(TransferEvent.TRANSFER_REQUEST_SENT_OR_RECEIVED)
            TransferState.ACCEPTED -> {
                walkToState(fsm, TransferState.PENDING_APPROVAL)
                fsm.transition(TransferEvent.TRANSFER_ACCEPTED)
            }
            TransferState.REJECTED -> {
                walkToState(fsm, TransferState.PENDING_APPROVAL)
                fsm.transition(TransferEvent.TRANSFER_REJECTED)
            }
            TransferState.TRANSFERRING -> {
                walkToState(fsm, TransferState.ACCEPTED)
                fsm.transition(TransferEvent.STREAM_STARTED)
            }
            TransferState.VERIFYING -> {
                walkToState(fsm, TransferState.TRANSFERRING)
                fsm.transition(TransferEvent.ALL_BYTES_RECEIVED)
            }
            TransferState.COMPLETED -> {
                walkToState(fsm, TransferState.VERIFYING)
                fsm.transition(TransferEvent.ALL_FILES_VERIFIED_AND_COMPLETED)
            }
            TransferState.CANCELLED -> {
                fsm.transition(TransferEvent.CANCEL_TRANSFER)
            }
            TransferState.FAILED -> {
                fsm.transition(TransferEvent.PREPARATION_ERROR)
            }
        }
    }

    private suspend fun walkLocalNodeTo(fsm: LocalNodeStateMachine, target: LocalNodeState) {
        when (target) {
            LocalNodeState.STOPPED -> Unit
            LocalNodeState.INITIALIZING -> fsm.transition(LocalNodeEvent.CMD_START_NODE)
            LocalNodeState.DISCOVERING -> {
                walkLocalNodeTo(fsm, LocalNodeState.INITIALIZING)
                fsm.transition(LocalNodeEvent.EVT_PORTS_BOUND)
            }
            LocalNodeState.AVAILABLE -> {
                walkLocalNodeTo(fsm, LocalNodeState.DISCOVERING)
                fsm.transition(LocalNodeEvent.EVT_INITIAL_ANNOUNCE_SENT)
            }
            LocalNodeState.FAULTED -> {
                walkLocalNodeTo(fsm, LocalNodeState.INITIALIZING)
                fsm.transition(LocalNodeEvent.EVT_BIND_FAILED)
            }
            LocalNodeState.SHUTTING_DOWN -> {
                walkLocalNodeTo(fsm, LocalNodeState.DISCOVERING)
                fsm.transition(LocalNodeEvent.CMD_STOP_NODE)
            }
        }
    }
}

