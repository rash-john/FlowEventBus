package com.floweventbus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine-first event bus modeled on greenrobot EventBus.
 *
 * Three ways to subscribe:
 *  - [register] an object with [Subscribe]-annotated (optionally `suspend`) methods, like EventBus.
 *  - [subscribe] a lambda and keep the returned [EventSubscription].
 *  - collect [events] as a cold [Flow].
 *
 * Two ways to post:
 *  - [post]: fire-and-forget, callable from anywhere (non-suspending handlers run synchronously, like EventBus).
 *  - [emit]: suspends until every handler, in every thread mode, has finished.
 */
class FlowEventBus internal constructor(builder: FlowEventBusBuilder) {

    private val logger = builder.logger
    private val logSubscriberExceptions = builder.logSubscriberExceptions
    private val logNoSubscriberMessages = builder.logNoSubscriberMessages
    private val sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent
    private val sendNoSubscriberEvent = builder.sendNoSubscriberEvent
    private val throwSubscriberException = builder.throwSubscriberException
    private val eventInheritance = builder.eventInheritance
    private val mainThreadSupport: MainThreadSupport? = builder.mainThreadSupport ?: MainThreadSupport.detect()
    private val asyncDispatcher = builder.asyncDispatcher
    private val indexes = builder.indexes.toList()

    private val lock = Any()
    private val subscriptionsByEventType = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Subscription>>()
    private val subscriptionsByOwner = IdentityHashMap<Any, MutableList<Subscription>>() // guarded by lock
    private val stickyEvents = ConcurrentHashMap<Class<*>, Any>()

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("FlowEventBus") +
            CoroutineExceptionHandler { _, t -> logger.log(FlowEventBusLogger.Level.ERROR, "Uncaught error in FlowEventBus", t) },
    )

    private class PendingPost(val subscription: Subscription, val event: Any, val done: CompletableDeferred<Unit>? = null)

    // Single sequential queues, like EventBus' HandlerPoster and BackgroundPoster.
    private val mainQueue: Channel<PendingPost>? = mainThreadSupport?.let { startQueue(it.dispatcher) }
    private val backgroundQueue: Channel<PendingPost> = startQueue(builder.backgroundDispatcher)

    private fun startQueue(dispatcher: CoroutineContext): Channel<PendingPost> {
        val channel = Channel<PendingPost>(Channel.UNLIMITED)
        scope.launch(dispatcher + QueueConsumer()) {
            for (pending in channel) {
                try {
                    invokeSubscriber(pending.subscription, pending.event)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.log(FlowEventBusLogger.Level.ERROR, "Subscriber failed on queue", t)
                } finally {
                    pending.done?.complete(Unit)
                }
            }
        }
        return channel
    }

    // ---------------------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------------------

    /** Registers all [Subscribe] methods of [subscriber]. Call [unregister] when done. */
    fun register(subscriber: Any) {
        val methods = SubscriberMethodFinder.find(subscriber.javaClass, indexes)
        if (methods.isEmpty()) {
            throw FlowEventBusException(
                "Subscriber ${subscriber.javaClass} and its super classes have no methods with the @Subscribe annotation",
            )
        }
        val stickyDeliveries = ArrayList<PendingPost>()
        synchronized(lock) {
            if (subscriptionsByOwner.containsKey(subscriber)) {
                throw FlowEventBusException("Subscriber ${subscriber.javaClass} is already registered")
            }
            for (method in methods) {
                val subscription = Subscription(
                    this, subscriber, method.eventType, method.threadMode, method.priority, method.sticky,
                ) { event -> method.invoke(subscriber, event) }
                addSubscriptionLocked(subscription, stickyDeliveries)
            }
        }
        deliverSticky(stickyDeliveries)
    }

    fun unregister(subscriber: Any) {
        synchronized(lock) {
            val subscriptions = subscriptionsByOwner.remove(subscriber)
            if (subscriptions == null) {
                logger.log(FlowEventBusLogger.Level.WARN, "Subscriber to unregister was not registered before: ${subscriber.javaClass}", null)
                return
            }
            subscriptions.forEach { it.active = false; subscriptionsByEventType[it.eventType]?.remove(it) }
        }
    }

    fun isRegistered(subscriber: Any): Boolean = synchronized(lock) { subscriptionsByOwner.containsKey(subscriber) }

    /**
     * Lambda subscription. Prefer the reified `bus.subscribe<MyEvent> { }` extension from Kotlin.
     * The handler is a suspend function and may call [cancelEventDelivery] when [threadMode] is POSTING.
     */
    fun <T : Any> subscribe(
        eventType: Class<T>,
        threadMode: ThreadMode = ThreadMode.POSTING,
        priority: Int = 0,
        sticky: Boolean = false,
        handler: suspend (T) -> Unit,
    ): EventSubscription {
        @Suppress("UNCHECKED_CAST")
        val subscription = Subscription(this, null, eventType, threadMode, priority, sticky, handler as suspend (Any) -> Unit)
        val stickyDeliveries = ArrayList<PendingPost>()
        synchronized(lock) { addSubscriptionLocked(subscription, stickyDeliveries) }
        deliverSticky(stickyDeliveries)
        return subscription
    }

    /**
     * Events of [eventType] as a cold [Flow]. Each collector gets its own subscription that lives as long as
     * the collection. Events are buffered, so a slow collector never blocks posters. Use `flowOn`/the collecting
     * scope to pick the thread. With [sticky], the latest sticky event is emitted first.
     */
    fun <T : Any> events(eventType: Class<T>, sticky: Boolean = false, priority: Int = 0): Flow<T> = callbackFlow {
        val subscription = subscribe(eventType, ThreadMode.POSTING, priority, sticky) { trySend(it) }
        awaitClose { subscription.unsubscribe() }
    }.buffer(Channel.UNLIMITED)

    internal fun removeSubscription(subscription: Subscription) {
        synchronized(lock) {
            subscription.active = false
            subscriptionsByEventType[subscription.eventType]?.remove(subscription)
            val owned = subscriptionsByOwner[subscription.owner] ?: return
            owned.remove(subscription)
            if (owned.isEmpty()) subscriptionsByOwner.remove(subscription.owner)
        }
    }

    private fun addSubscriptionLocked(subscription: Subscription, stickyDeliveries: MutableList<PendingPost>) {
        val list = subscriptionsByEventType.getOrPut(subscription.eventType) { CopyOnWriteArrayList() }
        // Keep sorted by priority (desc); equal priorities keep registration order.
        val index = list.indexOfFirst { subscription.priority > it.priority }.let { if (it < 0) list.size else it }
        list.add(index, subscription)
        subscriptionsByOwner.getOrPut(subscription.owner) { ArrayList() }.add(subscription)

        if (subscription.sticky) {
            if (eventInheritance) {
                for ((type, event) in stickyEvents) {
                    if (subscription.eventType.isAssignableFrom(type)) stickyDeliveries += PendingPost(subscription, event)
                }
            } else {
                stickyEvents[subscription.eventType]?.let { stickyDeliveries += PendingPost(subscription, it) }
            }
        }
    }

    private fun deliverSticky(deliveries: List<PendingPost>) {
        if (deliveries.isEmpty()) return
        launchNow { deliveries.forEach { deliver(it.subscription, it.event, null, null) } }
    }

    // ---------------------------------------------------------------------------------------------
    // Posting
    // ---------------------------------------------------------------------------------------------

    /**
     * Fire-and-forget post, callable from any thread (no coroutine needed). Delivery starts immediately in the
     * calling thread; non-suspending POSTING handlers have completed by the time this returns, exactly like
     * EventBus. A suspending handler continues in the background after it first suspends.
     */
    fun post(event: Any) = launchNow { dispatch(event, await = false) }

    /**
     * Suspends until every subscriber has handled [event], including queued (MAIN, MAIN_ORDERED, BACKGROUND)
     * and ASYNC ones. Called from a handler that itself runs on a queue, other queued handlers are not
     * awaited, since the queue can't run them until the current handler returns.
     */
    suspend fun emit(event: Any) = dispatch(event, await = true)

    private suspend fun dispatch(event: Any, await: Boolean) {
        val types = eventTypesFor(event.javaClass)
        val state = DeliveryState(event)
        val pending = if (await) ArrayList<Job>() else null
        var found = false
        withContext(state) {
            outer@ for (type in types) {
                val subscriptions = subscriptionsByEventType[type] ?: continue
                for (subscription in subscriptions) {
                    if (!subscription.active) continue
                    found = true
                    deliver(subscription, event, state, pending)
                    if (state.canceled) break@outer
                }
            }
        }
        pending?.joinAll()
        if (!found) onNoSubscriber(event)
    }

    /** Stores [event] (replacing the previous one of the same class) and posts it. */
    fun postSticky(event: Any) {
        stickyEvents[event.javaClass] = event
        post(event) // post after storing, so a subscriber may remove it right away
    }

    suspend fun emitSticky(event: Any) {
        stickyEvents[event.javaClass] = event
        emit(event)
    }

    fun <T : Any> getStickyEvent(eventType: Class<T>): T? = eventType.cast(stickyEvents[eventType])

    fun <T : Any> removeStickyEvent(eventType: Class<T>): T? = eventType.cast(stickyEvents.remove(eventType))

    /** Removes [event] only if it is still the current sticky event of its class. */
    fun removeStickyEvent(event: Any): Boolean = stickyEvents.remove(event.javaClass, event)

    fun removeAllStickyEvents() = stickyEvents.clear()

    fun hasSubscriberForEvent(eventClass: Class<*>): Boolean =
        eventTypesFor(eventClass).any { subscriptionsByEventType[it]?.isNotEmpty() == true }

    /**
     * Stops [event] from reaching lower-priority subscribers. Only allowed from a [ThreadMode.POSTING] handler
     * while it handles [event]. (It's `suspend` because the delivery state lives in the coroutine context.)
     */
    suspend fun cancelEventDelivery(event: Any) {
        val state = currentCoroutineContext()[DeliveryState]
            ?: throw FlowEventBusException("cancelEventDelivery may only be called from inside a POSTING event handler")
        if (state.event !== event) throw FlowEventBusException("Only the currently handled event may be aborted")
        if (state.currentThreadMode != ThreadMode.POSTING) {
            throw FlowEventBusException("Only ThreadMode.POSTING handlers may abort the incoming event")
        }
        state.canceled = true
    }

    /** Adds a Job to [pending] for every delivery that outlives this call, so [emit] can await it. */
    private suspend fun deliver(subscription: Subscription, event: Any, state: DeliveryState?, pending: MutableList<Job>?) {
        val main = mainThreadSupport
        // A queue runs one handler at a time; waiting on it from inside a queued handler would deadlock.
        val queuedPending = pending?.takeIf { currentCoroutineContext()[QueueConsumer] == null }
        when (subscription.threadMode) {
            ThreadMode.POSTING -> invokeDirect(subscription, event, state)
            ThreadMode.MAIN ->
                if (main == null || main.isMainThread()) invokeDirect(subscription, event, state)
                else enqueue(mainQueue!!, subscription, event, queuedPending)
            ThreadMode.MAIN_ORDERED ->
                if (main == null) invokeDirect(subscription, event, state)
                else enqueue(mainQueue!!, subscription, event, queuedPending)
            ThreadMode.BACKGROUND ->
                if (main != null && !main.isMainThread()) invokeDirect(subscription, event, state)
                else enqueue(backgroundQueue, subscription, event, queuedPending)
            ThreadMode.ASYNC -> scope.launch(asyncDispatcher) { invokeSubscriber(subscription, event) }.also { pending?.add(it) }
        }
    }

    private fun enqueue(queue: Channel<PendingPost>, subscription: Subscription, event: Any, pending: MutableList<Job>?) {
        val done = pending?.let { CompletableDeferred<Unit>().also(it::add) }
        queue.trySend(PendingPost(subscription, event, done))
    }

    private suspend fun invokeDirect(subscription: Subscription, event: Any, state: DeliveryState?) {
        val previous = state?.currentThreadMode
        state?.currentThreadMode = subscription.threadMode
        try {
            invokeSubscriber(subscription, event)
        } finally {
            state?.currentThreadMode = previous
        }
    }

    private suspend fun invokeSubscriber(subscription: Subscription, event: Any) {
        if (!subscription.active) return // unregistered while the event was queued
        try {
            subscription.handler(event)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onSubscriberException(subscription, event, t)
        }
    }

    private suspend fun onSubscriberException(subscription: Subscription, event: Any, cause: Throwable) {
        if (event is SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                logger.log(FlowEventBusLogger.Level.ERROR, "SubscriberExceptionEvent subscriber ${subscription.owner.javaClass} threw an exception", cause)
                logger.log(FlowEventBusLogger.Level.ERROR, "Initial event ${event.causingEvent} caused exception in ${event.causingSubscriber}", event.throwable)
            }
            return
        }
        if (throwSubscriberException) throw FlowEventBusException("Invoking subscriber failed", cause)
        if (logSubscriberExceptions) {
            logger.log(FlowEventBusLogger.Level.ERROR, "Could not dispatch event ${event.javaClass} to $subscription", cause)
        }
        if (sendSubscriberExceptionEvent) emit(SubscriberExceptionEvent(this, cause, event, subscription.owner))
    }

    private suspend fun onNoSubscriber(event: Any) {
        if (logNoSubscriberMessages) {
            logger.log(FlowEventBusLogger.Level.DEBUG, "No subscribers registered for event ${event.javaClass}", null)
        }
        if (sendNoSubscriberEvent && event !is NoSubscriberEvent && event !is SubscriberExceptionEvent) {
            emit(NoSubscriberEvent(this, event))
        }
    }

    /**
     * Starts [block] immediately in the current thread (Unconfined) so thread-sensitive decisions are made where
     * the caller is, and rethrows a synchronous failure (e.g. with throwSubscriberException) to the caller.
     * Nested posts from handlers are queued by the Unconfined event loop, matching EventBus' per-thread queue.
     */
    private fun launchNow(block: suspend () -> Unit) {
        val result = AtomicReference<Any?>(null)
        scope.launch(Dispatchers.Unconfined) {
            try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!result.compareAndSet(null, t)) throw t // caller already returned -> scope handler logs it
            }
        }
        val failure = result.getAndSet(RETURNED)
        if (failure is Throwable) throw failure
    }

    private fun eventTypesFor(eventClass: Class<*>): List<Class<*>> {
        if (!eventInheritance) return listOf(eventClass)
        return eventTypesCache.getOrPut(eventClass) {
            val types = LinkedHashSet<Class<*>>()
            var clazz: Class<*>? = eventClass
            while (clazz != null) {
                types += clazz
                addInterfaces(types, clazz.interfaces)
                clazz = clazz.superclass
            }
            types.toList()
        }
    }

    private fun addInterfaces(types: MutableSet<Class<*>>, interfaces: Array<Class<*>>) {
        for (i in interfaces) if (types.add(i)) addInterfaces(types, i.interfaces)
    }

    override fun toString() = "FlowEventBus[inheritance=$eventInheritance, main=${mainThreadSupport != null}]"

    /** Per-emit state carried in the coroutine context; replaces EventBus' ThreadLocal PostingThreadState. */
    private class DeliveryState(val event: Any) : AbstractCoroutineContextElement(DeliveryState) {
        companion object Key : CoroutineContext.Key<DeliveryState>

        @Volatile var canceled = false
        @Volatile var currentThreadMode: ThreadMode? = null
    }

    /** Marks the coroutine that drains a delivery queue. */
    private class QueueConsumer : AbstractCoroutineContextElement(QueueConsumer) {
        companion object Key : CoroutineContext.Key<QueueConsumer>
    }

    companion object {
        private val RETURNED = Any()
        private val eventTypesCache = ConcurrentHashMap<Class<*>, List<Class<*>>>()

        @Volatile
        private var defaultInstance: FlowEventBus? = null

        /** Process-wide bus, created lazily with default settings (or from [FlowEventBusBuilder.installDefaultFlowEventBus]). */
        @JvmStatic
        fun getDefault(): FlowEventBus =
            defaultInstance ?: synchronized(this) { defaultInstance ?: FlowEventBusBuilder().build().also { defaultInstance = it } }

        @JvmStatic
        fun builder(): FlowEventBusBuilder = FlowEventBusBuilder()

        /** Kotlin DSL: `FlowEventBus.build { eventInheritance = false }` */
        inline fun build(block: FlowEventBusBuilder.() -> Unit): FlowEventBus = builder().apply(block).build()

        internal fun installDefault(bus: FlowEventBus) = synchronized(this) {
            if (defaultInstance != null) {
                throw FlowEventBusException("Default instance already exists. Call installDefaultFlowEventBus() before using getDefault().")
            }
            defaultInstance = bus
        }

        /** For tests / hot reload. */
        @JvmStatic
        fun clearCaches() {
            SubscriberMethodFinder.clearCache()
            eventTypesCache.clear()
        }
    }
}
