package com.floweventbus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job

/** `bus.subscribe<LoginEvent>(ThreadMode.MAIN) { event -> ... }` */
inline fun <reified T : Any> FlowEventBus.subscribe(
    threadMode: ThreadMode = ThreadMode.POSTING,
    priority: Int = 0,
    sticky: Boolean = false,
    noinline handler: suspend (T) -> Unit,
): EventSubscription = subscribe(T::class.java, threadMode, priority, sticky, handler)

/**
 * Subscription that is removed automatically when [scope] is cancelled, e.g. `viewModelScope`.
 * ```
 * bus.subscribe<CartChanged>(viewModelScope) { reload() }
 * ```
 */
inline fun <reified T : Any> FlowEventBus.subscribe(
    scope: CoroutineScope,
    threadMode: ThreadMode = ThreadMode.POSTING,
    priority: Int = 0,
    sticky: Boolean = false,
    noinline handler: suspend (T) -> Unit,
): EventSubscription {
    val subscription = subscribe(T::class.java, threadMode, priority, sticky, handler)
    scope.coroutineContext.job.invokeOnCompletion { subscription.unsubscribe() }
    return subscription
}

/** `bus.events<LocationUpdate>().filter { ... }.collect { ... }` */
inline fun <reified T : Any> FlowEventBus.events(sticky: Boolean = false, priority: Int = 0): Flow<T> =
    events(T::class.java, sticky, priority)

inline fun <reified T : Any> FlowEventBus.getStickyEvent(): T? = getStickyEvent(T::class.java)

inline fun <reified T : Any> FlowEventBus.removeStickyEvent(): T? = removeStickyEvent(T::class.java)

inline fun <reified T : Any> FlowEventBus.hasSubscriberForEvent(): Boolean = hasSubscriberForEvent(T::class.java)
