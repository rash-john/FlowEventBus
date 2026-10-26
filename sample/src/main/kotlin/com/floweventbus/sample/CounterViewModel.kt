package com.floweventbus.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floweventbus.FlowEventBus
import com.floweventbus.ThreadMode
import com.floweventbus.subscribe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A ViewModel has no LifecycleOwner: the scope overload unsubscribes when viewModelScope is cancelled. */
class CounterViewModel : ViewModel() {
    private val _pings = MutableStateFlow(0)
    val pings: StateFlow<Int> = _pings

    init {
        FlowEventBus.getDefault().subscribe<Ping>(viewModelScope, ThreadMode.BACKGROUND) { _pings.value++ }
    }
}
