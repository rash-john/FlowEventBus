package com.floweventbus

/** Thrown for misuse of the bus, or when a subscriber fails and `throwSubscriberException` is enabled. */
class FlowEventBusException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Posted when an event had no subscribers (disable with `sendNoSubscriberEvent = false`). */
class NoSubscriberEvent(
    val bus: FlowEventBus,
    val originalEvent: Any,
)

/** Posted when a subscriber throws (disable with `sendSubscriberExceptionEvent = false`). */
class SubscriberExceptionEvent(
    val bus: FlowEventBus,
    val throwable: Throwable,
    val causingEvent: Any,
    /** The registered object, or the [EventSubscription] for lambda subscribers. */
    val causingSubscriber: Any,
)
