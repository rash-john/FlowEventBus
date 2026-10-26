package com.floweventbus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlowEventBusTest {

    open class BaseEvent
    class ChildEvent(val value: String = "") : BaseEvent()
    class OtherEvent

    private fun newBus(block: FlowEventBusBuilder.() -> Unit = {}) = FlowEventBus.build {
        logger = FlowEventBusLogger.None
        block()
    }

    class AnnotatedSubscriber {
        val received = Collections.synchronizedList(mutableListOf<String>())

        @Subscribe
        fun onChild(e: ChildEvent) { received += "child:${e.value}" }

        @Subscribe
        suspend fun onOther(e: OtherEvent) {
            delay(1)
            received += "other"
        }
    }

    @Test
    fun registerAndPost_deliversToPlainAndSuspendMethods() = runBlocking {
        val bus = newBus()
        val s = AnnotatedSubscriber()
        bus.register(s)
        bus.emit(ChildEvent("a"))
        bus.emit(OtherEvent())
        assertEquals(listOf("child:a", "other"), s.received)

        bus.unregister(s)
        assertFalse(bus.isRegistered(s))
        bus.emit(ChildEvent("b"))
        assertEquals(2, s.received.size)
    }

    @Test
    fun post_isSynchronousForNonSuspendingHandlers() {
        val bus = newBus()
        val got = mutableListOf<String>()
        bus.subscribe<ChildEvent> { got += it.value }
        bus.post(ChildEvent("x"))
        assertEquals(listOf("x"), got) // delivered before post() returned, like EventBus
    }

    @Test
    fun doubleRegister_throws() {
        val bus = newBus()
        val s = AnnotatedSubscriber()
        bus.register(s)
        assertFailsWith<FlowEventBusException> { bus.register(s) }
    }

    @Test
    fun eventInheritance_deliversToSuperTypeSubscribers() = runBlocking {
        val bus = newBus()
        val got = mutableListOf<String>()
        bus.subscribe<BaseEvent> { got += "base" }
        bus.subscribe<Any> { got += "any" }
        bus.emit(ChildEvent())
        assertEquals(listOf("base", "any"), got)

        val noInheritance = newBus { eventInheritance = false }
        val got2 = mutableListOf<String>()
        noInheritance.subscribe<BaseEvent> { got2 += "base" }
        noInheritance.emit(ChildEvent())
        assertTrue(got2.isEmpty())
    }

    @Test
    fun priority_andCancelEventDelivery() = runBlocking {
        val bus = newBus()
        val order = mutableListOf<Int>()
        bus.subscribe<ChildEvent>(priority = 1) { order += 1 }
        bus.subscribe<ChildEvent>(priority = 10) { order += 10; bus.cancelEventDelivery(it) }
        bus.subscribe<ChildEvent>(priority = 5) { order += 5 }
        bus.emit(ChildEvent())
        assertEquals(listOf(10), order)
    }

    @Test
    fun cancelEventDelivery_outsideHandler_throws() = runBlocking<Unit> {
        val bus = newBus()
        assertFailsWith<FlowEventBusException> { bus.cancelEventDelivery(ChildEvent()) }
    }

    @Test
    fun sticky_deliveredOnRegistrationAndViaFlow() = runBlocking {
        val bus = newBus()
        bus.postSticky(ChildEvent("first"))
        bus.postSticky(ChildEvent("latest"))

        var got: String? = null
        bus.subscribe<ChildEvent>(sticky = true) { got = it.value }
        assertEquals("latest", got)

        assertEquals("latest", bus.events<BaseEvent>(sticky = true).first().let { (it as ChildEvent).value })
        assertEquals("latest", bus.getStickyEvent<ChildEvent>()?.value)
        assertEquals("latest", bus.removeStickyEvent<ChildEvent>()?.value)
        assertNull(bus.getStickyEvent<ChildEvent>())
    }

    @Test
    fun flow_receivesEventsWhileCollecting() = runBlocking {
        val bus = newBus()
        val collected = async(Dispatchers.Default) { bus.events<ChildEvent>().take(3).toList().map { it.value } }
        withTimeout(2_000) { while (!bus.hasSubscriberForEvent<ChildEvent>()) yield() }
        listOf("1", "2", "3").forEach { bus.post(ChildEvent(it)) }
        assertEquals(listOf("1", "2", "3"), collected.await())
        withTimeout(2_000) { while (bus.hasSubscriberForEvent<ChildEvent>()) yield() } // unsubscribed on completion
    }

    @Test
    fun threadModes_useTheRightThreads() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val bus = newBus {
            mainThreadSupport = MainThreadSupport.of(mainDispatcher) { Thread.currentThread().name.startsWith("fake-main") }
        }
        val main = CompletableDeferred<String>()
        val mainOrdered = CompletableDeferred<String>()
        val background = CompletableDeferred<String>()
        val async = CompletableDeferred<String>()
        bus.subscribe<ChildEvent>(ThreadMode.MAIN) { main.complete(Thread.currentThread().name) }
        bus.subscribe<ChildEvent>(ThreadMode.MAIN_ORDERED) { mainOrdered.complete(Thread.currentThread().name) }
        bus.subscribe<ChildEvent>(ThreadMode.BACKGROUND) { background.complete(Thread.currentThread().name) }
        bus.subscribe<ChildEvent>(ThreadMode.ASYNC) { async.complete(Thread.currentThread().name) }

        // Post from the "main" thread: MAIN runs inline, BACKGROUND gets queued off main.
        kotlinx.coroutines.withContext(mainDispatcher) { bus.post(ChildEvent()) }
        withTimeout(2_000) {
            assertTrue(main.await().startsWith("fake-main"))
            assertTrue(mainOrdered.await().startsWith("fake-main"))
            assertFalse(background.await().startsWith("fake-main"))
            assertFalse(async.await().startsWith("fake-main"))
        }
        mainExecutor.shutdown()
    }

    @Test
    fun emit_awaitsQueuedAndAsyncHandlers() = runBlocking {
        val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val bus = newBus {
            mainThreadSupport = MainThreadSupport.of(mainDispatcher) { Thread.currentThread().name.startsWith("fake-main") }
        }
        val done = Collections.synchronizedSet(mutableSetOf<ThreadMode>())
        for (mode in listOf(ThreadMode.MAIN, ThreadMode.MAIN_ORDERED, ThreadMode.ASYNC)) {
            bus.subscribe<ChildEvent>(mode) { delay(50); done += mode }
        }
        bus.emit(ChildEvent()) // off main: MAIN is queued
        assertEquals(setOf(ThreadMode.MAIN, ThreadMode.MAIN_ORDERED, ThreadMode.ASYNC), done)

        bus.subscribe<OtherEvent>(ThreadMode.BACKGROUND) { delay(50); done += ThreadMode.BACKGROUND }
        kotlinx.coroutines.withContext(mainDispatcher) { bus.emit(OtherEvent()) } // on main: BACKGROUND is queued
        assertTrue(ThreadMode.BACKGROUND in done)
        mainExecutor.shutdown()
    }

    @Test
    fun emit_fromQueuedHandler_doesNotDeadlock() = runBlocking {
        val bus = newBus() // no main support: BACKGROUND is always queued
        val other = CompletableDeferred<Unit>()
        bus.subscribe<OtherEvent>(ThreadMode.BACKGROUND) { other.complete(Unit) }
        bus.subscribe<ChildEvent>(ThreadMode.BACKGROUND) { bus.emit(OtherEvent()) }
        withTimeout(2_000) {
            bus.emit(ChildEvent())
            other.await()
        }
    }

    @Test
    fun subscriberException_isReportedAsEvent() = runBlocking {
        val bus = newBus()
        val boom = IllegalStateException("boom")
        var reported: SubscriberExceptionEvent? = null
        bus.subscribe<ChildEvent> { throw boom }
        bus.subscribe<SubscriberExceptionEvent> { reported = it }
        bus.emit(ChildEvent())
        assertSame(boom, reported?.throwable)
    }

    @Test
    fun throwSubscriberException_rethrowsToPoster() {
        val bus = newBus { throwSubscriberException = true }
        bus.subscribe<ChildEvent> { error("boom") }
        assertFailsWith<FlowEventBusException> { bus.post(ChildEvent()) }
    }

    @Test
    fun noSubscriberEvent_isPosted() = runBlocking {
        val bus = newBus()
        var original: Any? = null
        bus.subscribe<NoSubscriberEvent> { original = it.originalEvent }
        val e = OtherEvent()
        bus.emit(e)
        assertSame(e, original)
    }

    @Test
    fun scopedSubscription_endsWithScope() = runBlocking {
        val bus = newBus()
        val scope = CoroutineScope(Job())
        val sub = bus.subscribe<ChildEvent>(scope) { }
        assertTrue(sub.isActive)
        scope.cancel()
        assertFalse(sub.isActive)
        assertFalse(bus.hasSubscriberForEvent<ChildEvent>())
    }

    @Test
    fun nestedPost_isQueuedUntilOuterDeliveryFinishes() {
        val bus = newBus()
        val log = mutableListOf<String>()
        bus.subscribe<ChildEvent> { log += "child-start"; bus.post(OtherEvent()); log += "child-end" }
        bus.subscribe<OtherEvent> { log += "other" }
        bus.post(ChildEvent())
        assertEquals(listOf("child-start", "child-end", "other"), log)
    }
}
