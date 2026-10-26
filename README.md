# FlowEventBus

A coroutine-first event bus for Android & Kotlin, modeled on greenrobot EventBus.
Same mental model (`register`, `@Subscribe`, `ThreadMode`, sticky events, priorities, cancellation), but every
delivery runs on coroutine dispatchers, handlers can be `suspend`, and events are available as `Flow`.

- [Project layout](#project-layout)
- [Setup](#setup)
- [Usage](#usage)
- [How it works (implementation)](#how-it-works-implementation)
- [Configuration reference](#configuration-reference)
- [Building & testing](#building--testing)
- [Mapping from EventBus](#mapping-from-eventbus)
- [Limitations / not ported](#limitations--not-ported)
- [License](#license)

---

## Project layout

```
FlowEventBus/
├── floweventbus/                    pure Kotlin/JVM core (only depends on kotlinx-coroutines-core)
│   └── src/main/kotlin/com/floweventbus/
│       ├── FlowEventBus.kt          the bus: registration, posting, delivery, sticky store, queues
│       ├── FlowEventBusBuilder.kt   configuration options + installDefaultFlowEventBus()
│       ├── FlowEventBusExtensions.kt reified Kotlin helpers: subscribe<T>, events<T>, getStickyEvent<T> …
│       ├── Subscribe.kt             @Subscribe(threadMode, sticky, priority) annotation
│       ├── SubscriberMethodFinder.kt finds @Subscribe methods via a generated index or reflection, cached
│       ├── Subscription.kt          EventSubscription handle + internal Subscription record
│       ├── ThreadMode.kt            POSTING / MAIN / MAIN_ORDERED / BACKGROUND / ASYNC
│       ├── MainThreadSupport.kt     "what is the main thread" abstraction, auto-detects Dispatchers.Main
│       ├── FlowEventBusLogger.kt    logging interface (Console, None)
│       └── Events.kt                FlowEventBusException, NoSubscriberEvent, SubscriberExceptionEvent
│   └── src/main/resources/META-INF/proguard/floweventbus.pro   R8 rules shipped in the jar
│
├── floweventbus-android/            Android add-on (depends on :floweventbus)
│   └── src/main/kotlin/com/floweventbus/android/
│       ├── AndroidLogger.kt         Logcat logger + FlowEventBusBuilder.android()
│       └── LifecycleExtensions.kt   registerWithLifecycle, subscribe(owner), LifecycleOwner.onEvent<T>
│
└── floweventbus-ksp/                optional KSP processor: generates a SubscriberIndex
    └── src/main/kotlin/com/floweventbus/ksp/SubscriberIndexProcessor.kt
```

| Module | Type | Depends on |
|---|---|---|
| `:floweventbus` | Kotlin/JVM library (`java-library`) | `kotlinx-coroutines-core` (api) |
| `:floweventbus-android` | Android library, minSdk 23, compileSdk 37 | `:floweventbus`, `kotlinx-coroutines-android`, `androidx.lifecycle:lifecycle-runtime-ktx` |
| `:floweventbus-ksp` | KSP processor (`ksp(...)` only, not on the runtime classpath) | `symbol-processing-api` |

Toolchain: Kotlin 2.0.21, KSP 2.0.21-1.0.28, coroutines 1.9.0, AGP 8.13.2, JVM 17. Group `com.floweventbus`, version `0.2.0`.

---

## Setup

Publish locally with `./gradlew publishToMavenLocal`, then add `mavenLocal()` in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
```

Then:

```kotlin
dependencies {
    implementation("com.floweventbus:floweventbus-android:0.2.0") // Android app
    // implementation("com.floweventbus:floweventbus:0.2.0")      // plain JVM / shared module
}
```

Or from source, as Gradle modules: `implementation(project(":floweventbus-android"))`.

Optional, in `Application.onCreate()` (must run **before** anything calls `FlowEventBus.getDefault()`):

```kotlin
FlowEventBus.builder().android().apply {
    throwSubscriberException = BuildConfig.DEBUG
}.installDefaultFlowEventBus()
```

### Subscriber index (optional)

`register` finds `@Subscribe` methods by reflection, once per class. To skip that, let KSP generate an
index at compile time, in each module that has subscribers:

```kotlin
plugins { id("com.google.devtools.ksp") version "2.0.21-1.0.28" } // match your Kotlin version

ksp { arg("floweventbus.index", "com.example.app.AppEventBusIndex") } // unique name per module

dependencies { ksp("com.floweventbus:floweventbus-ksp:0.2.0") }
```

```kotlin
FlowEventBus.builder().android()
    .addIndex(AppEventBusIndex) // once per module index
    .installDefaultFlowEventBus()
```

The generated code calls each handler directly. A class it can't call (private or local, a private or
protected handler, a generic event type) is left out with a build warning and is found by reflection at
runtime, so adding the index never breaks anything.

---

## Usage

There are three ways to subscribe. All of them go through the same internal delivery pipeline.

### 1. EventBus style – annotated methods

```kotlin
data class RideRequested(val rideId: String)

class RideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // registers on START, unregisters on STOP – no onStart/onStop boilerplate
        FlowEventBus.getDefault().registerWithLifecycle(this, this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRideRequested(event: RideRequested) = showRequest(event.rideId)

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    suspend fun cacheRide(event: RideRequested) = rideDao.insert(event.rideId) // suspend handlers work
}

FlowEventBus.getDefault().post(RideRequested("42"))
```

Without the Android helper: `bus.register(this)` / `bus.unregister(this)` / `bus.isRegistered(this)`.

Rules for `@Subscribe` methods (checked at `register`, violations throw `FlowEventBusException`):
- exactly one parameter (the event); `suspend` is allowed
- not `static`, event type not primitive
- the class (or a superclass) must have at least one `@Subscribe` method
- the same object can't be registered twice

### 2. Lambdas

```kotlin
val bus = FlowEventBus.getDefault()

val sub = bus.subscribe<RideRequested>(ThreadMode.MAIN) { show(it) }
sub.unsubscribe()          // or sub.close() – EventSubscription is Closeable

// removed automatically when the scope is cancelled (e.g. ViewModel cleared)
bus.subscribe<RideRequested>(viewModelScope) { repo.refresh() }

// Android: removed when the LifecycleOwner is destroyed, MAIN by default
bus.subscribe<RideRequested>(owner = this) { show(it) }

// Android: active only while STARTED (repeatOnLifecycle), delivered on main
onEvent<RideRequested> { show(it) }
viewLifecycleOwner.onEvent<LocationChanged>(sticky = true) { moveMarker(it) }
```

### 3. Flow

```kotlin
bus.events<LocationChanged>()
    .filter { it.accuracy < 20 }
    .debounce(500)
    .collect { upload(it) }
```

### Posting

| Call | Behaviour |
|---|---|
| `post(event)` | From any thread, no coroutine needed. Non-suspending POSTING handlers finish before it returns (like EventBus). |
| `emit(event)` | `suspend`; waits until every handler finishes, in every thread mode (including suspending ones). |
| `postSticky` / `emitSticky` | Also stores the event; new `sticky = true` subscribers receive the latest one. |

Sticky store helpers: `getStickyEvent<T>()`, `removeStickyEvent<T>()`, `removeStickyEvent(event)`
(only if it's still the current one), `removeAllStickyEvents()`. Introspection: `hasSubscriberForEvent<T>()`.

### Thread modes

| Mode | Runs on |
|---|---|
| `POSTING` (default) | Caller's thread/coroutine. Only mode allowed to `cancelEventDelivery`. |
| `MAIN` | Main thread; inline if already on main, otherwise queued. |
| `MAIN_ORDERED` | Always queued on main, posting never blocks. |
| `BACKGROUND` | Inline if poster is off main, else a single sequential background queue. |
| `ASYNC` | New coroutine on `Dispatchers.IO` per event. |

Without a main dispatcher (plain JVM tests) MAIN modes behave like POSTING, as in EventBus.
Inject your own with `mainThreadSupport = MainThreadSupport.of(dispatcher) { isMain }`.

### Priority & cancellation

```kotlin
bus.subscribe<PushMessage>(priority = 100) { msg ->
    if (msg.isDuplicate) bus.cancelEventDelivery(msg) // lower priorities won't see it
}
```

### Errors

A throwing subscriber doesn't break delivery to others. It's logged and a `SubscriberExceptionEvent` is posted.
Events with no subscribers produce a `NoSubscriberEvent`. Both can be disabled in the builder;
`throwSubscriberException = true` rethrows to the poster instead.

```kotlin
bus.subscribe<SubscriberExceptionEvent> { crashReporter.log(it.throwable, it.causingEvent) }
```

---

## How it works (implementation)

### Big picture

```
            register(obj)                 subscribe<T>{}              events<T>()
                 │                             │                          │
   SubscriberMethodFinder (KSP index or        │              callbackFlow { subscribe(POSTING) { trySend } }
   reflection, cached per class)               │                          │
                 └──────────────┬──────────────┴──────────────────────────┘
                                ▼
                      Subscription(eventType, threadMode, priority, sticky, handler)
                                │
             subscriptionsByEventType: Class → CopyOnWriteArrayList<Subscription>  (sorted by priority)
             subscriptionsByOwner:     owner → List<Subscription>                   (for unregister)
                                │
   post(e) ─► launchNow ─► dispatch(e) ─► for each type in eventTypesFor(e.class)
                                           for each active subscription (high → low priority)
                                              deliver(sub, e)  ──► by ThreadMode:
                                                 POSTING      → invoke now
                                                 MAIN         → invoke now if on main, else mainQueue
                                                 MAIN_ORDERED → mainQueue
                                                 BACKGROUND   → invoke now if off main, else backgroundQueue
                                                 ASYNC        → scope.launch(asyncDispatcher)
                                              stop if cancelEventDelivery() was called
                                           emit only: join queued + ASYNC jobs
                                           none found → NoSubscriberEvent
```

### State held by a bus

All in `FlowEventBus.kt`:

| Field | Type | Purpose |
|---|---|---|
| `subscriptionsByEventType` | `ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<Subscription>>` | Read on every post without locking. Copy-on-write means a post iterates a stable snapshot even if someone unregisters mid-delivery. |
| `subscriptionsByOwner` | `IdentityHashMap<Any, MutableList<Subscription>>` | Lets `unregister(obj)` find all of an object's subscriptions. Identity-based so `equals()` overrides on subscribers don't collide. Guarded by `lock`. |
| `stickyEvents` | `ConcurrentHashMap<Class<*>, Any>` | Latest sticky event per exact class. |
| `scope` | `SupervisorJob + CoroutineExceptionHandler` | Owns the queue consumers and ASYNC coroutines; one failure never kills the others, uncaught errors are logged. |
| `mainQueue` / `backgroundQueue` | `Channel<PendingPost>(UNLIMITED)` | Sequential delivery queues (see below). `mainQueue` is null when there is no main thread. |

All writes (`register`, `unregister`, `subscribe`, `removeSubscription`) take one `lock`; reads in the
post path don't lock at all.

### Registration

**Annotated objects** – `register(obj)`:
1. `SubscriberMethodFinder.find(obj.javaClass, indexes)` walks the class and its superclasses (stopping at
   `java.*`, `android.*`, `kotlin.*`, … system classes). For each class it asks the indexes added with
   `addIndex` first, and falls back to `declaredMethods` when none covers it. Results are cached per class
   in a `ConcurrentHashMap`, so the lookup is paid once per class.
2. With reflection, a method with `(Event)` is a normal handler. A method with `(Event, Continuation)` is a
   Kotlin `suspend fun` – that is how the compiler lowers it – so it gets a suspend-aware invoker.
3. Overrides: the scan goes subclass → superclass and dedupes on `name>eventType`, so an override in a
   subclass wins and the super version isn't registered twice. Bridge/synthetic methods are skipped.
4. Each method becomes a `Subscription` whose handler is `method.invoke(obj, event)`. For an indexed
   method, `invoke` is generated code such as `{ s, e -> (s as MySubscriber).onLogin(e as LoginEvent) }`.

**Calling a suspend method by reflection** (`SubscriberMethodFinder.reflectiveInvoker`):

```kotlin
suspendCoroutineUninterceptedOrReturn<Any?> { cont -> method.invoke(target, event, cont) }
```

We hand the method our own continuation. If it completes without suspending it returns its result directly;
if it suspends it returns `COROUTINE_SUSPENDED` and resumes us later – exactly what a normal Kotlin call does.
`InvocationTargetException` is unwrapped so subscribers see the real exception.

**Lambdas** – `subscribe(type, mode, priority, sticky, handler)` creates a `Subscription` with no owner object;
the subscription itself acts as the owner, so `unsubscribe()` removes just that one.

**Insertion order**: `addSubscriptionLocked` inserts before the first subscription with a lower priority,
so the list stays sorted high → low and equal priorities keep registration order.

**Flow** – `events<T>()` is a `callbackFlow` that does a POSTING lambda subscription calling `trySend`, and
`awaitClose { unsubscribe() }`. `.buffer(UNLIMITED)` means a slow collector never blocks the poster.
Each collector gets its own subscription; cancel the collection and the subscription goes away.

**Scoped/lifecycle helpers**:
- `subscribe<T>(scope)` hooks `scope.job.invokeOnCompletion { unsubscribe() }`.
- `subscribe<T>(owner: LifecycleOwner)` = the same with `owner.lifecycleScope` (ends on DESTROYED).
- `LifecycleOwner.onEvent<T>` = `lifecycleScope.launch { repeatOnLifecycle(minState) { bus.events<T>().collect(handler) } }`,
  so collection starts/stops with the lifecycle and handlers run on `Dispatchers.Main`.
- `registerWithLifecycle(obj, owner, minState = STARTED)` adds a `LifecycleEventObserver` that calls
  `register`/`unregister` whenever the state crosses `minState`, and removes itself on DESTROYED.

**Unregister** marks each subscription `active = false` *and* removes it from the lists. The `active` flag
matters for events already sitting in a queue: `invokeSubscriber` checks it and drops them, so nothing
is delivered to an object after `unregister` returns.

### Posting & delivery

**`emit(event)`** (`post` runs the same loop without step 4's wait):
1. `eventTypesFor(event.class)` – with `eventInheritance = true` (default) this is the class, all
   superclasses and all interfaces (recursively), cached globally. Otherwise just the class.
2. A fresh `DeliveryState(event)` is added to the coroutine context with `withContext(state)`.
3. For each type, for each **active** subscription in priority order → `deliver(...)`. After each one,
   if `state.canceled` the whole loop stops.
4. `deliver` returns a `Job` for each queued or ASYNC delivery; `emit` waits for all of them with `joinAll`.
5. If no subscription was found → `onNoSubscriber` (log + post `NoSubscriberEvent`).

**`post(event)` / `launchNow`** – lets plain, non-coroutine code post while keeping EventBus' synchronous feel:

```kotlin
scope.launch(Dispatchers.Unconfined) { dispatch(event, await = false) }
```

`Dispatchers.Unconfined` starts the coroutine **immediately in the calling thread**, so:
- thread checks (`isMainThread()`) are made on the poster's thread, which is what MAIN/BACKGROUND need;
- non-suspending POSTING handlers have finished when `post` returns;
- a suspending handler runs until its first suspension, then continues in the background;
- **nested posts** (a handler posting another event) are queued by Unconfined's per-thread event loop and run
  after the outer delivery finishes – the same ordering EventBus gets from its per-thread queue.

`launchNow` also captures an exception thrown *synchronously* (e.g. with `throwSubscriberException`) and
rethrows it to the caller of `post`. An `AtomicReference` with a `RETURNED` marker decides who owns a
failure: if it happens after `post` already returned, it's rethrown inside the coroutine and the scope's
exception handler logs it instead.

### Thread mode internals

`deliver` picks the path (`main` is the bus' `MainThreadSupport`, may be null):

| Mode | main present | main == null |
|---|---|---|
| POSTING | invoke directly | invoke directly |
| MAIN | on main → direct; else → `mainQueue` | direct |
| MAIN_ORDERED | `mainQueue` | direct |
| BACKGROUND | off main → direct; on main → `backgroundQueue` | `backgroundQueue` |
| ASYNC | `scope.launch(asyncDispatcher)` | same |

**Queues** replace EventBus' `HandlerPoster` / `BackgroundPoster`. Each is an unlimited `Channel` plus one
consumer coroutine launched in `scope` on its dispatcher:

```kotlin
for (pending in channel) {
    try { invokeSubscriber(pending.subscription, pending.event) } finally { pending.done?.complete(Unit) }
}
```

One consumer = strictly sequential, FIFO delivery. The main queue runs on `Dispatchers.Main`; the background
queue on `Dispatchers.IO.limitedParallelism(1)` (a serial view of IO). Enqueueing uses `trySend`, which
never blocks on an unlimited channel. ASYNC gets a new coroutine per event, so ASYNC handlers can run in
parallel.

`emit` also waits for queued and ASYNC deliveries: each queued `PendingPost` carries a `CompletableDeferred`
that the consumer completes once the handler returns. The exception is an `emit` made from a handler that is
itself running on a queue. Its queued deliveries are not awaited, because a consumer handles one item at a
time and waiting on a queue from inside one would deadlock. `post` never waits.

**Main-thread detection** (`MainThreadSupport.detect()`): touches `Dispatchers.Main.immediate`; on plain
JVM without a main dispatcher this throws and `null` is returned (→ MAIN modes fall back to POSTING).
On Android (`kotlinx-coroutines-android` comes with `:floweventbus-android`) it works, and
`isMainThread()` = `!Main.immediate.isDispatchNeeded()`. Tests/desktop can inject
`MainThreadSupport.of(dispatcher) { Thread.currentThread() == uiThread }`.

### Cancellation (`cancelEventDelivery`) – why it's `suspend`

EventBus keeps "which event is being delivered on this thread" in a `ThreadLocal`. That breaks with
suspending handlers, which can resume on a different thread. FlowEventBus instead puts a `DeliveryState`
element in the **coroutine context** for the duration of `emit`. It holds the event, a `canceled` flag and
the `currentThreadMode` (set by `invokeDirect` around each direct call).

`cancelEventDelivery(event)` reads `currentCoroutineContext()[DeliveryState]` and throws `FlowEventBusException` if:
- there is no state (called outside a handler),
- `event` isn't the event currently being delivered,
- the current handler isn't `POSTING`.

Otherwise it sets `canceled = true` and `emit` stops after the current subscriber. Queued/ASYNC handlers
run without a `DeliveryState`, so they can't cancel – same rule as EventBus.

### Sticky events

- `postSticky(e)` stores `stickyEvents[e.class] = e` **then** posts, so a subscriber may remove it straight away.
- When a `sticky = true` subscription is added, `addSubscriptionLocked` collects the matching stored events
  (with inheritance: every stored event whose class is assignable to the subscribed type) into a list.
  After the lock is released, `deliverSticky` delivers them via `launchNow` using the subscription's
  thread mode. Delivering outside the lock avoids deadlocks if the handler registers/posts itself.
- `events<T>(sticky = true)` / `onEvent<T>(sticky = true)` get the same treatment, so a Flow collector
  starts with the latest value (and `onEvent` gets it again each time the lifecycle restarts).

### Error handling

`invokeSubscriber` catches everything except `CancellationException` (always rethrown to keep coroutine
cancellation working) and calls `onSubscriberException`:

1. If the failing event was itself a `SubscriberExceptionEvent` → only log (prevents infinite loops).
2. `throwSubscriberException = true` → wrap in `FlowEventBusException` and rethrow to the poster.
3. Else log (if `logSubscriberExceptions`) and emit `SubscriberExceptionEvent(bus, throwable, event, owner)`
   (if `sendSubscriberExceptionEvent`). Other subscribers still get the event.

Queue consumers additionally catch and log anything escaping, so one bad handler can't kill a queue.
`NoSubscriberEvent` is never emitted for a `NoSubscriberEvent` or `SubscriberExceptionEvent` (again, no loops).

### Default instance

`FlowEventBus.getDefault()` is lazy, double-checked locking on a `@Volatile` field. `installDefaultFlowEventBus()`
throws if a default already exists – call it once, early. `FlowEventBus.clearCaches()` clears the subscriber-method and
event-type caches (tests / hot reload).

### R8 / ProGuard

`@Subscribe` methods are found by reflection, so they must survive shrinking. The rules ship in the jar at
`META-INF/proguard/floweventbus.pro` (R8 picks them up automatically):

```
-keepattributes *Annotation*
-keep @interface com.floweventbus.Subscribe
-keepclassmembers class * { @com.floweventbus.Subscribe <methods>; }
-keep enum com.floweventbus.ThreadMode { *; }
```

Lambda and Flow subscriptions need no rules. Indexed methods are called directly, so they wouldn't need
the rules either, but they are kept anyway since the same jar serves both paths.

---

## Configuration reference

`FlowEventBus.builder()` / `FlowEventBus.build { … }`:

| Option | Default | Effect |
|---|---|---|
| `logSubscriberExceptions` | `true` | Log when a handler throws. |
| `logNoSubscriberMessages` | `true` | Log (DEBUG) when an event has no subscribers. |
| `sendSubscriberExceptionEvent` | `true` | Post `SubscriberExceptionEvent` on handler failure. |
| `sendNoSubscriberEvent` | `true` | Post `NoSubscriberEvent` when nobody listens. |
| `throwSubscriberException` | `false` | Rethrow handler failures to the poster (direct deliveries). Good for debug/tests. |
| `eventInheritance` | `true` | Deliver to subscribers of supertypes/interfaces too. Turn off for a bit of speed. |
| `logger` | `FlowEventBusLogger.Console` | `FlowEventBusLogger.None`, `AndroidLogger(tag)` (via `.android()`), or your own `fun interface`. |
| `mainThreadSupport` | `null` (auto-detect) | Custom main thread. |
| `backgroundDispatcher` | `Dispatchers.IO.limitedParallelism(1)` | BACKGROUND queue. **Must be sequential.** |
| `asyncDispatcher` | `Dispatchers.IO` | ASYNC handlers. |
| `addIndex(index)` | none | Use a KSP-generated `SubscriberIndex` instead of reflection for the classes it covers. |

Then `.build()` for a private bus or `.installDefaultFlowEventBus()` to make it the default.

---

## Building & testing

```bash
./gradlew build                 # compile all modules + run tests
./gradlew :floweventbus:test    # core unit tests (plain JVM, no device needed)
./gradlew publishToMavenLocal   # install all artifacts to ~/.m2 (unsigned)
```

`floweventbus/src/test/kotlin/com/floweventbus/FlowEventBusTest.kt` covers:

- annotated plain + `suspend` handlers, double register throws
- `post` is synchronous for non-suspending handlers
- event inheritance
- priority ordering + `cancelEventDelivery`, and cancelling outside a handler throws
- sticky delivery on register and via Flow
- Flow receives events while collecting
- thread modes land on the right threads (with an injected main thread)
- `emit` waits for queued and ASYNC handlers, and an `emit` from a queued handler doesn't deadlock
- `SubscriberExceptionEvent`, `throwSubscriberException`, `NoSubscriberEvent`
- scoped subscriptions end with their scope
- nested posts are queued until the outer delivery finishes

`SubscriberIndexTest.kt` runs `floweventbus-ksp` on the test sources (`kspTest`) and checks the generated
`TestEventBusIndex`: annotation values, superclasses, private classes left to reflection, and a bus that
mixes indexed and reflected subscribers.

### Releasing

Publishing uses gradle-maven-publish-plugin; coordinates and POM metadata live in `gradle.properties`.
Bump `VERSION_NAME`, then run `./gradlew publishToMavenLocal` (or add your own repository).

---

## Mapping from EventBus

| EventBus | FlowEventBus |
|---|---|
| `EventBus.getDefault()` | `FlowEventBus.getDefault()` |
| `EventBus.builder()...installDefaultEventBus()` | `FlowEventBus.builder()...installDefaultFlowEventBus()` |
| `register / unregister / isRegistered` | same |
| `post / postSticky` | same, plus suspending `emit / emitSticky` |
| `getStickyEvent(Class)` | same, plus `getStickyEvent<T>()` |
| `@Subscribe(threadMode, sticky, priority)` | same; method may be `suspend` |
| `EventBusAnnotationProcessor` + `addIndex` | `floweventbus-ksp` + `addIndex` |
| `ThreadLocal` posting state | `CoroutineContext` element (`DeliveryState`) |
| `HandlerPoster` / `BackgroundPoster` / `AsyncPoster` | `Channel` queues on `Dispatchers.Main` / serial IO / `Dispatchers.IO` |
| — | `subscribe<T> {}`, `events<T>(): Flow<T>`, Lifecycle helpers |

---

## Limitations / not ported

- `AsyncExecutor` / `ThrowableFailureEvent` utilities – plain coroutines cover this.
- An `emit` called from a queued handler (MAIN off-main, MAIN_ORDERED, BACKGROUND on-main) doesn't wait for
  other queued handlers, since that could deadlock.
- Sticky events are keyed by exact class: one stored event per class.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
