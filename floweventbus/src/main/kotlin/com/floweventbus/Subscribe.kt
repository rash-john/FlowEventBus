package com.floweventbus

/**
 * Marks a method as an event handler for [FlowEventBus.register].
 *
 * The method takes exactly one parameter (the event) and may be a `suspend fun`.
 * ```
 * @Subscribe(threadMode = ThreadMode.MAIN)
 * fun onLogin(event: LoginEvent) { ... }
 *
 * @Subscribe(threadMode = ThreadMode.BACKGROUND)
 * suspend fun onSync(event: SyncRequested) { repository.sync() }
 * ```
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Subscribe(
    val threadMode: ThreadMode = ThreadMode.POSTING,
    /** If true, the most recent sticky event of this type (see [FlowEventBus.postSticky]) is delivered on registration. */
    val sticky: Boolean = false,
    /** Higher priority subscribers receive events first. */
    val priority: Int = 0,
)
