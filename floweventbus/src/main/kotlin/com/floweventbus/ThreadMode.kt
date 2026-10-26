package com.floweventbus

/**
 * Where a subscriber is invoked. Same semantics as greenrobot EventBus, but every mode
 * is backed by a coroutine dispatcher and handlers may be `suspend`.
 */
enum class ThreadMode {
    /**
     * Default. Invoked directly in the poster's coroutine/thread. The only mode that may call
     * [FlowEventBus.cancelEventDelivery]. A suspending handler suspends the poster (`emit`) until it completes.
     */
    POSTING,

    /**
     * Invoked on the main dispatcher. If the poster is already on the main thread the handler runs
     * directly (blocking the post), otherwise it's queued. Without main-thread support, behaves like [POSTING].
     */
    MAIN,

    /** Always queued on the main dispatcher; posting never blocks. Without main support, behaves like [POSTING]. */
    MAIN_ORDERED,

    /**
     * Invoked off the main thread. If the poster is not on the main thread the handler runs directly,
     * otherwise it is queued on a single sequential background queue. Without main support, always queued.
     */
    BACKGROUND,

    /** Each event is handled in a new coroutine on the async dispatcher (default `Dispatchers.IO`). */
    ASYNC,
}
