package com.floweventbus.sample

/** Delivered to every thread mode. */
class Ping(val id: Int)

/** Sticky: the latest value is replayed to new subscribers. */
data class LocationChanged(val lat: Double, val lng: Double)

/** Priority & cancellation: a high-priority subscriber drops duplicates. */
data class ChatMessage(val text: String, val duplicate: Boolean)

/** Inheritance: subscribers of [Animal] also receive [Dog]. */
open class Animal(val name: String)
class Dog : Animal("dog")

/** Its subscriber throws, which produces a SubscriberExceptionEvent. */
class Crash

/** Nobody subscribes, which produces a NoSubscriberEvent. */
class Unheard

/** Posted in bursts and debounced through the Flow API. */
class Keystroke(val char: Char)
