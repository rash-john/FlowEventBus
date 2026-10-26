package com.floweventbus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.EmptyCoroutineContext

/** Tells the bus what "main thread" means. Pluggable for tests and non-Android UI toolkits. */
interface MainThreadSupport {
    val dispatcher: CoroutineDispatcher
    fun isMainThread(): Boolean

    companion object {
        /**
         * Uses `Dispatchers.Main` when a main dispatcher is installed (e.g. `kotlinx-coroutines-android`).
         * Returns null on plain JVM, in which case MAIN modes fall back to POSTING like EventBus does.
         */
        @JvmStatic
        fun detect(): MainThreadSupport? = try {
            val main = Dispatchers.Main
            main.immediate.isDispatchNeeded(EmptyCoroutineContext) // throws if no main dispatcher is present
            CoroutinesMainThreadSupport(main)
        } catch (_: Throwable) {
            null
        }

        /** Custom main thread, e.g. a single-thread executor in tests. */
        @JvmStatic
        fun of(dispatcher: CoroutineDispatcher, isMainThread: () -> Boolean): MainThreadSupport =
            object : MainThreadSupport {
                override val dispatcher = dispatcher
                override fun isMainThread() = isMainThread()
            }
    }
}

/** `Main.immediate` only needs a dispatch when we are *not* on the main thread. */
class CoroutinesMainThreadSupport(private val main: MainCoroutineDispatcher) : MainThreadSupport {
    override val dispatcher: CoroutineDispatcher get() = main
    override fun isMainThread(): Boolean = !main.immediate.isDispatchNeeded(EmptyCoroutineContext)
}
