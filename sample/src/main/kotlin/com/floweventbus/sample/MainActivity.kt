package com.floweventbus.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.floweventbus.FlowEventBus
import com.floweventbus.NoSubscriberEvent
import com.floweventbus.Subscribe
import com.floweventbus.SubscriberExceptionEvent
import com.floweventbus.ThreadMode
import com.floweventbus.android.onEvent
import com.floweventbus.android.registerWithLifecycle
import com.floweventbus.android.subscribe
import com.floweventbus.events
import com.floweventbus.getStickyEvent
import com.floweventbus.hasSubscriberForEvent
import com.floweventbus.removeStickyEvent
import com.floweventbus.subscribe
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LogLine(val message: String, val thread: String)

class MainActivity : ComponentActivity() {
    private val bus = FlowEventBus.getDefault()
    private val viewModel: CounterViewModel by viewModels()
    private var count = 0

    private val logLines = mutableStateListOf<LogLine>()
    private var location by mutableStateOf<LocationChanged?>(null)

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SampleTheme { SampleScreen() } }

        // 1. @Subscribe methods below: registered on START, unregistered on STOP
        bus.registerWithLifecycle(this, this)

        // 2. Lambda tied to the Activity: receives events until onDestroy, even while stopped
        bus.subscribe<Ping>(owner = this) { log("subscribe(owner) lambda #${it.id}") }

        // 3. Flow collector, only while STARTED; sticky replays the latest location after rotation
        onEvent<LocationChanged>(sticky = true) { location = it }

        // 4. Flow operators: a burst of keystrokes becomes one line
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bus.events<Keystroke>().map { it.char }.debounce(300).collect { log("Flow debounce: last key '$it'") }
            }
        }

        // 5. Priority & cancellation: priority 100 runs first and drops duplicates (POSTING only)
        bus.subscribe<ChatMessage>(lifecycleScope, priority = 100) {
            if (it.duplicate) {
                log("priority 100: cancelled duplicate \"${it.text}\"")
                bus.cancelEventDelivery(it)
            }
        }
        bus.subscribe<ChatMessage>(lifecycleScope) { log("priority 0: received \"${it.text}\"") }

        // 6. Errors: a throwing subscriber doesn't break delivery, it produces a SubscriberExceptionEvent
        bus.subscribe<SubscriberExceptionEvent>(lifecycleScope) { log("SubscriberExceptionEvent: ${it.throwable.message}") }
        bus.subscribe<NoSubscriberEvent>(lifecycleScope) { log("NoSubscriberEvent: ${it.originalEvent.javaClass.simpleName}") }

        // 7. ViewModel subscription via viewModelScope: see CounterViewModel, shown in the top bar
    }

    // --- @Subscribe: one handler per thread mode ---

    @Subscribe // ThreadMode.POSTING: the poster's thread
    fun onPingPosting(event: Ping) = log("POSTING #${event.id}")

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPingMain(event: Ping) = log("MAIN #${event.id}")

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED, priority = -1)
    suspend fun onPingMainOrdered(event: Ping) {
        delay(300) // suspend handlers are allowed; emit() waits for this one
        log("MAIN_ORDERED suspend (+300 ms) #${event.id}")
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onPingBackground(event: Ping) = log("BACKGROUND #${event.id}")

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onPingAsync(event: Ping) = log("ASYNC #${event.id}")

    @Subscribe // event inheritance: also receives Dog
    fun onAnimal(event: Animal) = log("Animal subscriber got ${event.javaClass.simpleName} \"${event.name}\"")

    @Subscribe
    fun onCrash(event: Crash): Unit = throw IllegalStateException("boom from onCrash")

    // --- Demos ---

    private class Demo(val label: String, val action: () -> Unit)

    private class Section(val title: String, val demos: List<Demo>)

    private val sections = listOf(
        Section("Delivery", listOf(
            Demo("post(Ping)") { bus.post(Ping(++count)) },
            Demo("emit(Ping)") {
                lifecycleScope.launch {
                    val id = ++count
                    bus.emit(Ping(id))
                    log("emit #$id returned after every handler")
                }
            },
            Demo("post(Dog) → Animal") { bus.post(Dog()) },
        )),
        Section("Sticky", listOf(
            Demo("postSticky(Location)") { bus.postSticky(LocationChanged(23.8 + ++count / 100.0, 90.4)) },
            Demo("Read & clear") {
                log("getStickyEvent: ${bus.getStickyEvent<LocationChanged>()}")
                log("removeStickyEvent: ${bus.removeStickyEvent<LocationChanged>()}")
            },
        )),
        Section("Priority, Flow & errors", listOf(
            Demo("Chat + duplicate") {
                bus.post(ChatMessage("hello", duplicate = false))
                bus.post(ChatMessage("hello", duplicate = true))
            },
            Demo("Type \"flow\"") { "flow".forEach { bus.post(Keystroke(it)) } },
            Demo("Subscriber throws") { bus.post(Crash()) },
            Demo("Nobody listens") {
                log("hasSubscriberForEvent<Unheard>: ${bus.hasSubscriberForEvent<Unheard>()}")
                bus.post(Unheard())
            },
        )),
    )

    // --- UI ---

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SampleScreen() {
        val pings by viewModel.pings.collectAsState()
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF5B3CC4)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("FlowEventBus", fontWeight = FontWeight.SemiBold)
                            Text(
                                "ViewModel received $pings ${if (pings == 1) "ping" else "pings"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                })
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StickyCard(location)
                sections.forEach { DemoSection(it) }
                EventLog(Modifier.weight(1f).padding(bottom = 12.dp))
            }
        }
    }

    @Composable
    private fun StickyCard(location: LocationChanged?) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Sticky location", style = MaterialTheme.typography.labelMedium)
                    AnimatedContent(location, label = "location") {
                        Text(
                            if (it == null) "none yet" else "%.2f, %.2f".format(it.lat, it.lng),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun DemoSection(section: Section) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                section.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                section.demos.forEach { demo ->
                    FilledTonalButton(
                        onClick = demo.action,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 34.dp),
                    ) { Text(demo.label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }

    @Composable
    private fun EventLog(modifier: Modifier) {
        val listState = rememberLazyListState()
        LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.lastIndex) }

        Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = ConsoleBackground) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Event log", color = ConsoleText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { logLines.clear() }) { Text("Clear", color = Color(0xFFCBBEFF)) }
                }
                if (logLines.isEmpty()) {
                    Text(
                        "Tap a button above. Each handler logs the thread it ran on.",
                        color = ConsoleText.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    itemsIndexed(logLines) { _, line -> LogRow(line) }
                }
            }
        }
    }

    @Composable
    private fun LogRow(line: LogLine) {
        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            val main = line.thread == "main"
            Text(
                if (main) "main" else "w-" + line.thread.substringAfterLast('-'),
                color = if (main) Color(0xFF7CF2E3) else Color(0xFFFFB960),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background((if (main) Color(0xFF7CF2E3) else Color(0xFFFFB960)).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .widthIn(min = 44.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(line.message, color = ConsoleText, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }

    /** Thread-safe: handlers call this from POSTING, BACKGROUND and ASYNC threads. */
    private fun log(message: String) {
        val thread = Thread.currentThread().name
        Log.i("FlowEventBusSample", "${message.padEnd(42)} [$thread]")
        runOnUiThread { logLines += LogLine(message, thread) }
    }
}
