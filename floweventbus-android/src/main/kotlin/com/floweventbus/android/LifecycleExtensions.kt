package com.floweventbus.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floweventbus.EventSubscription
import com.floweventbus.FlowEventBus
import com.floweventbus.ThreadMode
import com.floweventbus.events
import com.floweventbus.subscribe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Registers an annotated [subscriber] while [owner] is at least [minState] and unregisters below it -
 * replaces the manual register in onStart / unregister in onStop boilerplate.
 * ```
 * override fun onCreate(...) { FlowEventBus.getDefault().registerWithLifecycle(this, this) }
 * ```
 */
fun FlowEventBus.registerWithLifecycle(
    subscriber: Any,
    owner: LifecycleOwner,
    minState: Lifecycle.State = Lifecycle.State.STARTED,
) {
    require(minState != Lifecycle.State.DESTROYED && minState != Lifecycle.State.INITIALIZED) {
        "minState must be CREATED, STARTED or RESUMED"
    }
    val lifecycle = owner.lifecycle
    lifecycle.addObserver(object : LifecycleEventObserver {
        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            val state = lifecycle.currentState
            val shouldBeRegistered = state.isAtLeast(minState)
            val registered = isRegistered(subscriber)
            if (shouldBeRegistered && !registered) register(subscriber)
            if (!shouldBeRegistered && registered) unregister(subscriber)
            if (state == Lifecycle.State.DESTROYED) lifecycle.removeObserver(this)
        }
    })
}

/**
 * Lambda subscription removed when [owner] is destroyed. Events arrive even while stopped -
 * use [onEvent] if you only want them while visible.
 */
inline fun <reified T : Any> FlowEventBus.subscribe(
    owner: LifecycleOwner,
    threadMode: ThreadMode = ThreadMode.MAIN,
    priority: Int = 0,
    sticky: Boolean = false,
    noinline handler: suspend (T) -> Unit,
): EventSubscription = subscribe(owner.lifecycleScope, threadMode, priority, sticky, handler)

/**
 * Collects events of [T] on the main thread only while [this] is at least [minState]
 * (via `repeatOnLifecycle`). Events posted while below [minState] are not delivered; pass
 * `sticky = true` to receive the latest sticky event each time the owner comes back.
 * ```
 * onEvent<RideAccepted> { showRide(it) }
 * ```
 */
inline fun <reified T : Any> LifecycleOwner.onEvent(
    bus: FlowEventBus = FlowEventBus.getDefault(),
    minState: Lifecycle.State = Lifecycle.State.STARTED,
    sticky: Boolean = false,
    noinline handler: suspend (T) -> Unit,
): Job = lifecycleScope.launch {
    repeatOnLifecycle(minState) {
        bus.events<T>(sticky).collect { handler(it) }
    }
}
