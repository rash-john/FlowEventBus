package com.floweventbus

import java.io.Closeable

/** Handle returned for lambda subscriptions. */
interface EventSubscription : Closeable {
    val isActive: Boolean
    fun unsubscribe()
    override fun close() = unsubscribe()
}

internal class Subscription(
    private val bus: FlowEventBus,
    ownerOrNull: Any?,
    val eventType: Class<*>,
    val threadMode: ThreadMode,
    val priority: Int,
    val sticky: Boolean,
    val handler: suspend (Any) -> Unit,
) : EventSubscription {
    /** Annotated subscriber object, or this handle for lambda subscriptions. */
    val owner: Any = ownerOrNull ?: this

    @Volatile
    var active: Boolean = true

    override val isActive: Boolean get() = active
    override fun unsubscribe() = bus.removeSubscription(this)
    override fun toString() =
        "Subscription(${if (owner === this) "lambda" else owner.javaClass.name} -> ${eventType.name}, $threadMode)"
}
