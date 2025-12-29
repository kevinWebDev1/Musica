package com.github.musicyou.sync.transport

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransportManagerTest {

    @Test
    fun `connect should connect all underlying transports`() = runTest {
        val t1 = MockTransportLayer()
        val t2 = MockTransportLayer()
        val manager = TransportManager(listOf(t1, t2))

        manager.connect()

        assertThat(t1.connectionState.value).isEqualTo(TransportLayer.ConnectionState.CONNECTED)
        assertThat(t2.connectionState.value).isEqualTo(TransportLayer.ConnectionState.CONNECTED)
        assertThat(manager.connectionState.value).isEqualTo(TransportLayer.ConnectionState.CONNECTED)
    }

    @Test
    fun `disconnect should disconnect all underlying transports`() = runTest {
        val t1 = MockTransportLayer()
        val manager = TransportManager(listOf(t1))
        
        manager.connect()
        manager.disconnect()

        assertThat(t1.connectionState.value).isEqualTo(TransportLayer.ConnectionState.DISCONNECTED)
        assertThat(manager.connectionState.value).isEqualTo(TransportLayer.ConnectionState.DISCONNECTED)
    }

    @Test
    fun `send should broadcast to all transports`() = runTest {
        val t1 = MockTransportLayer()
        val t2 = MockTransportLayer()
        val manager = TransportManager(listOf(t1, t2))

        val data = "Hello".toByteArray()
        manager.connect()
        manager.send(data)

        assertThat(t1.sentMessages).contains(data)
        assertThat(t2.sentMessages).contains(data)
    }

    @Test
    fun `incomingMessages should aggregate from all transports`() = runTest {
        val t1 = MockTransportLayer()
        val t2 = MockTransportLayer()
        val manager = TransportManager(listOf(t1, t2), TestScope(UnconfinedTestDispatcher(testScheduler)))

        val receivedMessages = mutableListOf<ByteArray>()
        
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
             manager.incomingMessages.collect { receivedMessages.add(it) }
        }

        val msg1 = "Msg1".toByteArray()
        val msg2 = "Msg2".toByteArray()

        t1.simulateIncomingMessage(msg1)
        t2.simulateIncomingMessage(msg2)

        assertThat(receivedMessages).hasSize(2)
        // Order might vary, but both should be present
        assertThat(receivedMessages.any { it.contentEquals(msg1) }).isTrue()
        assertThat(receivedMessages.any { it.contentEquals(msg2) }).isTrue()
    }
}
