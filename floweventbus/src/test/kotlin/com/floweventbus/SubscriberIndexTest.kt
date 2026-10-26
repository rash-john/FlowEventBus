package com.floweventbus

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SubscriberIndexTest {

    class Event(val value: String)

    open class BaseSubscriber {
        val received = mutableListOf<String>()

        @Subscribe(priority = 5)
        fun onBase(e: Event) { received += "base:${e.value}" }
    }

    class IndexedSubscriber : BaseSubscriber() {
        @Subscribe(threadMode = ThreadMode.BACKGROUND, sticky = true)
        suspend fun onSuspend(e: Event) {
            delay(1)
            received += "suspend:${e.value}"
        }

        @Subscribe
        internal fun onInternal(e: Event) { received += "internal:${e.value}" }
    }

    private class PrivateSubscriber {
        val received = mutableListOf<String>()

        @Subscribe
        fun onEvent(e: Event) { received += e.value }
    }

    @Test
    fun index_coversVisibleClassesWithAnnotationValues() {
        val methods = assertNotNull(TestEventBusIndex.subscriberMethods(IndexedSubscriber::class.java))
        assertEquals(setOf("onSuspend", "onInternal"), methods.map { it.methodName }.toSet())
        val suspendMethod = methods.single { it.methodName == "onSuspend" }
        assertEquals(ThreadMode.BACKGROUND, suspendMethod.threadMode)
        assertEquals(true, suspendMethod.sticky)
        assertSame(Event::class.java, suspendMethod.eventType)
        assertEquals(5, TestEventBusIndex.subscriberMethods(BaseSubscriber::class.java)!!.single().priority)
        assertNull(TestEventBusIndex.subscriberMethods(PrivateSubscriber::class.java)) // left to reflection
    }

    @Test
    fun finder_usesIndexPerClassAndWalksSuperClasses() {
        FlowEventBus.clearCaches()
        val found = SubscriberMethodFinder.find(IndexedSubscriber::class.java, listOf(TestEventBusIndex))
        val indexed = TestEventBusIndex.subscriberMethods(IndexedSubscriber::class.java)!! +
            TestEventBusIndex.subscriberMethods(BaseSubscriber::class.java)!!
        assertEquals(indexed.toSet(), found.toSet()) // same instances: no reflection involved
        FlowEventBus.clearCaches()
    }

    @Test
    fun busWithIndex_deliversToIndexedAndReflectedSubscribers() = runBlocking {
        FlowEventBus.clearCaches()
        val bus = FlowEventBus.build {
            logger = FlowEventBusLogger.None
            addIndex(TestEventBusIndex)
        }
        val indexed = IndexedSubscriber()
        val reflected = PrivateSubscriber()
        bus.register(indexed)
        bus.register(reflected)
        bus.emit(Event("a"))
        assertEquals(listOf("base:a", "internal:a", "suspend:a"), indexed.received.sorted())
        assertEquals(listOf("a"), reflected.received)
        FlowEventBus.clearCaches()
    }
}
