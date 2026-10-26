<div align="center">

# 🚌 FlowEventBus

**A coroutine-first event bus for Android & Kotlin, inspired by [greenrobot EventBus](https://github.com/greenrobot/EventBus).**

[![JitPack](https://jitpack.io/v/rash-john/FlowEventBus.svg)](https://jitpack.io/#rash-john/FlowEventBus)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.21-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Coroutines](https://img.shields.io/badge/coroutines-1.9.0-7F52FF.svg)](https://github.com/Kotlin/kotlinx.coroutines)
[![Android](https://img.shields.io/badge/minSdk-23-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)

</div>

If you know [greenrobot EventBus](https://github.com/greenrobot/EventBus), you already know FlowEventBus:
`register`, `@Subscribe`, `ThreadMode`, sticky events, priorities and cancellation all work the same way.
The difference is under the hood: every delivery runs on coroutine dispatchers, handlers can be `suspend`,
and any event type can be collected as a `Flow`.

```kotlin
@Subscribe(threadMode = ThreadMode.MAIN)
suspend fun onRideRequested(event: RideRequested) { … }   // suspend handlers ✅

bus.events<LocationChanged>().debounce(500).collect { … } // events as Flow ✅

onEvent<RideRequested> { show(it) }                       // lifecycle-aware, no unregister ✅
```

## ✨ Features

- 🔁 **Drop-in mental model.** The same API shape as greenrobot EventBus, so migrating is mostly renaming ([mapping table](#-mapping-from-eventbus)).
- ⏸️ **`suspend` handlers.** `@Subscribe` methods and lambdas can suspend, and `emit()` waits until every one has finished.
- 🌊 **Flow API.** `events<T>()` gives you a cold `Flow<T>` that works with every operator you already use.
- 🧵 **Five thread modes:** `POSTING`, `MAIN`, `MAIN_ORDERED`, `BACKGROUND` and `ASYNC`, all backed by coroutine dispatchers.
- ♻️ **Lifecycle-aware.** `registerWithLifecycle`, `subscribe(owner)` and `onEvent<T>` clean up after themselves.
- 📌 **Sticky events, priorities and cancellation**, matching EventBus.
- ⚡ **Optional KSP index.** Look up subscribers at compile time and skip reflection.
- 🛡️ **R8 ready.** Keep rules ship inside the jar.

## 📑 Contents

- [Installation](#-installation)
- [Usage](#-usage)
- [Sample app](#-sample-app)
- [How it works](#-how-it-works-implementation)
- [Configuration reference](#%EF%B8%8F-configuration-reference)
- [Project layout](#-project-layout)
- [Building & testing](#-building--testing)
- [Mapping from EventBus](#-mapping-from-eventbus)
- [Limitations](#%EF%B8%8F-limitations--not-ported)
- [License](#-license)

---

## 📦 Installation

FlowEventBus is published on [**JitPack**](https://jitpack.io/#rash-john/FlowEventBus).

**1. Add the JitPack repository** in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

<details>
<summary>Groovy (<code>settings.gradle</code>)</summary>

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```
</details>

**2. Add the dependency** in your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.rash-john.FlowEventBus:floweventbus-android:0.2.1") // Android app
    // implementation("com.github.rash-john.FlowEventBus:floweventbus:0.2.1")      // plain JVM / shared module
}
```

<details>
<summary>Groovy (<code>build.gradle</code>)</summary>

```groovy
dependencies {
    implementation 'com.github.rash-john.FlowEventBus:floweventbus-android:0.2.1'
}
```
</details>

| Artifact | Use it for |
|---|---|
| `floweventbus-android` | Android apps. Includes the core module, Logcat logging, `Dispatchers.Main` and the Lifecycle helpers. |
| `floweventbus` | Pure Kotlin/JVM modules (only depends on `kotlinx-coroutines-core`). |
| `floweventbus-ksp` | Optional compile-time subscriber index, added with `ksp(...)`. |

> [!TIP]
> To try the latest unreleased commit, use `main-SNAPSHOT` as the version. JitPack builds it on first request.

**3. Optional:** configure the default bus in `Application.onCreate()`. This must run **before** anything calls `FlowEventBus.getDefault()`:

```kotlin
FlowEventBus.builder().android().apply {
    throwSubscriberException = BuildConfig.DEBUG
}.installDefaultFlowEventBus()
```

<details>
<summary>Other ways to install: Maven Local, or from source</summary>

- **Maven Local:** run `./gradlew publishToMavenLocal`, add `mavenLocal()` to your repositories and use the `com.floweventbus` group, for example `com.floweventbus:floweventbus-android:0.2.1`.
- **From source:** include the modules in your build and depend on them with `implementation(project(":floweventbus-android"))`.
</details>

### ⚡ Subscriber index (optional)

`register` finds `@Subscribe` methods by reflection, once per class. To skip that, let KSP generate an
index at compile time, in each module that has subscribers:

```kotlin
plugins { id("com.google.devtools.ksp") version "2.0.21-1.0.28" } // match your Kotlin version

ksp { arg("floweventbus.index", "com.example.app.AppEventBusIndex") } // unique name per module

dependencies { ksp("com.github.rash-john.FlowEventBus:floweventbus-ksp:0.2.1") }
```

```kotlin
FlowEventBus.builder().android()
    .addIndex(AppEventBusIndex) // once per module index
    .installDefaultFlowEventBus()
```

The generated code calls each handler directly. Some classes can't be called that way: private or local
classes, private or protected handlers, and generic event types. Those are left out with a build warning
and found by reflection at runtime, so adding the index never breaks anything.

---

## 🚀 Usage

There are three ways to subscribe, plus Android lifecycle helpers built on top of them. All of them go through the same internal delivery pipeline.

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
```

On Android, see [Android lifecycle](#%EF%B8%8F-android-lifecycle) for subscriptions that follow a `LifecycleOwner`.

### 3. Flow

```kotlin
bus.events<LocationChanged>()
    .filter { it.accuracy < 20 }
    .debounce(500)
    .collect { upload(it) }
```

### ♻️ Android lifecycle

`floweventbus-android` has three helpers that tie a subscription to a `LifecycleOwner`, so you never have to
unregister by hand. Pick one by **when** you want events to arrive:

| Helper | Subscribes as | Receives events | Cleaned up | Thread |
|---|---|---|---|---|
| `bus.registerWithLifecycle(obj, owner, minState)` | `@Subscribe` methods | while state ≥ `minState` (default `STARTED`) | unregistered below `minState`, observer removed on `DESTROYED` | per `@Subscribe(threadMode)` |
| `bus.subscribe<T>(owner) { }` | lambda | from now until destroyed, **even while stopped** | on `DESTROYED` | `MAIN` by default (any `ThreadMode`) |
| `owner.onEvent<T> { }` | `Flow` collector | while state ≥ `minState` (default `STARTED`) | stops below `minState`, restarts above it, ends on `DESTROYED` | main thread |

**Activity**

```kotlin
class RideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bus = FlowEventBus.getDefault()

        // @Subscribe methods: registered on START, unregistered on STOP
        bus.registerWithLifecycle(this, this)

        // visible-only lambda, re-delivers the latest sticky event every time the screen comes back
        onEvent<LocationChanged>(sticky = true) { moveMarker(it) }

        // runs even in the background, until onDestroy
        bus.subscribe<RideCancelled>(owner = this) { finish() }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRideRequested(event: RideRequested) = showRequest(event.rideId)
}
```

**Fragment:** use `viewLifecycleOwner` in `onViewCreated` for anything that touches views. A fragment's
own lifecycle outlives its view, so collecting with `this` would update a destroyed view, and you'd get a
duplicate collector every time the view is recreated.

```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    viewLifecycleOwner.onEvent<RideRequested> { binding.title.text = it.rideId }
}
```

**ViewModel:** it has no `LifecycleOwner`, so use the scope overload. The subscription is removed when
`viewModelScope` is cancelled in `onCleared()`:

```kotlin
init {
    FlowEventBus.getDefault().subscribe<RideRequested>(viewModelScope, ThreadMode.BACKGROUND) { repo.refresh(it.rideId) }
}
```

**Options**

- `minState` can be `CREATED`, `STARTED` or `RESUMED`. For `registerWithLifecycle`, `INITIALIZED` and `DESTROYED` throw `IllegalArgumentException`.
- `onEvent(bus = myBus)` uses a bus other than the default.
- `onEvent` returns a `Job` and `subscribe(owner)` returns an `EventSubscription`. Cancel or `unsubscribe()` either one to stop early.

> [!IMPORTANT]
> - Events posted while an owner is below `minState` are **dropped**, not queued. Post with `postSticky` if
>   the screen must see the latest value when it comes back (`onEvent(sticky = true)` or `@Subscribe(sticky = true)`).
> - `onEvent` collects a `Flow`, so `priority` and `cancelEventDelivery` don't apply. Use `subscribe(owner, priority = …)` if you need them.
> - Don't mix `registerWithLifecycle` with a manual `register(obj)` for the same object. The manual call throws if the object is already registered.

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

## 📱 Sample app

The [`sample/`](sample) module is a one-screen Jetpack Compose (Material 3) app with a button for every
feature. Each handler writes to an on-screen event log, tagged with the thread it ran on (also sent to Logcat
under `FlowEventBusSample`). It supports light and dark themes, and its launcher icon shows one publisher
streaming events to three subscribers.

```bash
./gradlew :sample:installDebug
```

| Button | What it shows | API |
|---|---|---|
| `post(Ping)` | One handler per thread mode, plus a lambda tied to the Activity and a ViewModel counter (under the title) | `@Subscribe(threadMode = …)`, `subscribe(owner)`, `subscribe(viewModelScope)` |
| `emit(Ping)` | `emit` returns only after every handler, including a `suspend` one with a 300 ms delay | `emit`, `suspend` handlers |
| `postSticky(Location)` | The "Sticky location" card updates and keeps its value after rotation | `postSticky`, `onEvent(sticky = true)` |
| `Read & clear` | Reads, then removes, the stored event | `getStickyEvent<T>()`, `removeStickyEvent<T>()` |
| `Chat + duplicate` | The priority 100 subscriber cancels the duplicate before priority 0 sees it | `priority`, `cancelEventDelivery` |
| `post(Dog) → Animal` | A subscriber for the superclass receives the subclass | event inheritance |
| `Subscriber throws` | Delivery survives; the error arrives as an event | `SubscriberExceptionEvent` |
| `Nobody listens` | No subscribers, reported as an event | `hasSubscriberForEvent<T>()`, `NoSubscriberEvent` |
| `Type "flow"` | Four quick posts collapse into one line | `events<T>()` + Flow operators |

Tapping through it gives a log like this:

```
POSTING #1                                 [main]
MAIN #1                                    [main]
BACKGROUND #1                              [DefaultDispatcher-worker-1]
ASYNC #1                                   [DefaultDispatcher-worker-2]
MAIN_ORDERED suspend (+300 ms) #1          [main]
emit #2 returned after every handler       [main]
priority 0: received "hello"               [main]
priority 100: cancelled duplicate "hello"  [main]
Animal subscriber got Dog "dog"            [main]
SubscriberExceptionEvent: boom from onCrash [main]
NoSubscriberEvent: Unheard                 [main]
Flow debounce: last key 'w'                [main]
```

Setup worth copying:

- [`SampleApp.kt`](sample/src/main/kotlin/com/floweventbus/sample/SampleApp.kt): `installDefaultFlowEventBus()` with the Logcat logger and the KSP index.
- [`sample/build.gradle.kts`](sample/build.gradle.kts): the `ksp { arg("floweventbus.index", …) }` setup that generates `SampleEventBusIndex`.
- [`MainActivity.kt`](sample/src/main/kotlin/com/floweventbus/sample/MainActivity.kt): every subscription style in one place.
- [`CounterViewModel.kt`](sample/src/main/kotlin/com/floweventbus/sample/CounterViewModel.kt): subscribing from a ViewModel.

The sample depends on the modules in this repo (`project(":floweventbus-android")`). In your own app, use the
[JitPack coordinates](#-installation) instead.

---

## 🔍 How it works (implementation)

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

## ⚙️ Configuration reference

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

## 🗂️ Project layout

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
├── floweventbus-ksp/                optional KSP processor: generates a SubscriberIndex
│   └── src/main/kotlin/com/floweventbus/ksp/SubscriberIndexProcessor.kt
│
└── sample/                          demo app covering every feature (not published)
    └── src/main/kotlin/com/floweventbus/sample/
        ├── SampleApp.kt             default bus setup with the KSP index
        ├── MainActivity.kt          all subscriptions + Compose screen: one button per feature, event log
        ├── Theme.kt                 Material 3 light/dark colors matching the launcher icon
        ├── CounterViewModel.kt      ViewModel subscription via viewModelScope
        └── Events.kt                the sample's event classes
```

| Module | Type | Depends on |
|---|---|---|
| `:floweventbus` | Kotlin/JVM library (`java-library`) | `kotlinx-coroutines-core` (api) |
| `:floweventbus-android` | Android library, minSdk 23, compileSdk 36 | `:floweventbus`, `kotlinx-coroutines-android`, `androidx.lifecycle:lifecycle-runtime-ktx` |
| `:floweventbus-ksp` | KSP processor (`ksp(...)` only, not on the runtime classpath) | `symbol-processing-api` |
| `:sample` | Android app, not published | `:floweventbus-android`, `ksp(:floweventbus-ksp)`, Compose Material 3, `androidx.activity:activity-compose` |

Toolchain: Kotlin 2.0.21, KSP 2.0.21-1.0.28, coroutines 1.9.0, AGP 8.13.2, JVM 17. Group `com.github.rash-john.FlowEventBus` on JitPack (`com.floweventbus` for local builds), version `0.2.1`.

---

## 🧪 Building & testing

```bash
./gradlew build                 # compile all modules + run tests
./gradlew :floweventbus:test    # core unit tests (plain JVM, no device needed)
./gradlew publishToMavenLocal   # install all artifacts to ~/.m2 (unsigned)
./gradlew :sample:installDebug  # install the sample app on a device or emulator
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

Releases are built by [JitPack](https://jitpack.io/#rash-john/FlowEventBus) from git tags, using `jitpack.yml`
(JDK 17, published under `com.github.rash-john.FlowEventBus`). To cut a release:

```bash
# bump VERSION_NAME in gradle.properties, commit, then:
git tag 0.3.0 && git push origin 0.3.0
```

JitPack builds the tag the first time someone requests it, or you can start the build from the JitPack page.
POM metadata lives in `gradle.properties` (gradle-maven-publish-plugin).

---

## 🔄 Mapping from EventBus

| [greenrobot EventBus](https://github.com/greenrobot/EventBus) | FlowEventBus |
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

## ⚠️ Limitations / not ported

- `AsyncExecutor` / `ThrowableFailureEvent` utilities – plain coroutines cover this.
- An `emit` called from a queued handler (MAIN off-main, MAIN_ORDERED, BACKGROUND on-main) doesn't wait for
  other queued handlers, since that could deadlock.
- Sticky events are keyed by exact class: one stored event per class.

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).

## 🙏 Acknowledgements

FlowEventBus is a coroutine port of the ideas in [greenrobot EventBus](https://github.com/greenrobot/EventBus)
by [greenrobot](https://greenrobot.org/eventbus/). Thanks to its authors for the original design.

<div align="center">

If FlowEventBus helps you, consider giving it a ⭐

</div>
