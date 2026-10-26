package com.floweventbus

fun interface FlowEventBusLogger {
    enum class Level { DEBUG, WARN, ERROR }

    fun log(level: Level, message: String, throwable: Throwable?)

    companion object {
        @JvmField
        val Console = FlowEventBusLogger { level, message, throwable ->
            val stream = if (level == Level.ERROR) System.err else System.out
            stream.println("[FlowEventBus] $level: $message")
            throwable?.printStackTrace(stream)
        }

        @JvmField
        val None = FlowEventBusLogger { _, _, _ -> }
    }
}
