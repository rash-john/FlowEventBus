package com.floweventbus.android

import android.util.Log
import com.floweventbus.FlowEventBusBuilder
import com.floweventbus.FlowEventBusLogger

class AndroidLogger(private val tag: String = "FlowEventBus") : FlowEventBusLogger {
    override fun log(level: FlowEventBusLogger.Level, message: String, throwable: Throwable?) {
        when (level) {
            FlowEventBusLogger.Level.DEBUG -> Log.d(tag, message, throwable)
            FlowEventBusLogger.Level.WARN -> Log.w(tag, message, throwable)
            FlowEventBusLogger.Level.ERROR -> Log.e(tag, message, throwable)
        }
    }
}

/** Logs to Logcat. Main-thread support is detected automatically via `Dispatchers.Main`. */
fun FlowEventBusBuilder.android(tag: String = "FlowEventBus"): FlowEventBusBuilder = apply { logger = AndroidLogger(tag) }
