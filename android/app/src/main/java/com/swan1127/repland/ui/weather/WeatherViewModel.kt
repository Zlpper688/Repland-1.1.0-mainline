package com.swan1127.repland.ui.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swan1127.repland.domain.model.NoOpWeatherPlaceSearch
import com.swan1127.repland.domain.model.WeatherPlace
import com.swan1127.repland.domain.model.WeatherPlaceSearch
import com.swan1127.repland.domain.model.WeatherPreferences
import com.swan1127.repland.domain.model.WeatherReading
import com.swan1127.repland.domain.model.WeatherSource
import com.swan1127.repland.domain.ports.WeatherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The weather choices screen: whether the widget says anything about the sky, and about which place.
 *
 * **Why the port and the `NoOp` matter here.** A release build has no network, so the search behind
 * this screen does not exist. The view model does not pretend otherwise — [networkAvailable] is
 * derived from the object the container actually handed it, the same way
 * [com.swan1127.repland.ui.profile.UsageSummaryViewModel] does, so the UI can say "this build has no
 * weather service" instead of showing a search box that returns nothing and reads as a bug.
 *
 * **Why picking is not detecting.** Nothing here can fill in a city on its own: a place arrives
 * through [search] and then through [choose], which is the app's standing rule that it asks for no
 * location permission and invents no coordinates.
 */
class WeatherViewModel(
    private val repository: WeatherRepository,
    private val source: WeatherSource,
    private val placeSearch: WeatherPlaceSearch,
) : ViewModel() {

    val networkAvailable: Boolean = placeSearch !== NoOpWeatherPlaceSearch

    private val mutableState = MutableStateFlow(WeatherUiState(loading = true))
    val state = mutableState.asStateFlow()

    init {
        repository.observePreferences()
            .onEach { preferences -> mutableState.update { it.copy(loading = false, preferences = preferences) } }
            .launchIn(viewModelScope)
        repository.observeCachedReading()
            .onEach { reading -> mutableState.update { it.copy(cached = reading) } }
            .launchIn(viewModelScope)
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(enabled)
            // Switching it on is the moment the user expects the row to appear, so the first reading
            // is asked for here rather than at the next widget redraw — otherwise "on" would mean
            // "on, once something else happens to wake the widget".
            if (enabled && mutableState.value.preferences.place != null) refresh()
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query, results = emptyList(), message = null) }
    }

    /** One lookup, on request. No debounce and no per-keystroke call: this hits a shared free tier. */
    fun search() {
        val query = mutableState.value.query.trim()
        if (query.isEmpty() || !networkAvailable) return
        mutableState.update { it.copy(searching = true, results = emptyList(), message = null) }
        viewModelScope.launch {
            val found = runCatching { placeSearch.search(query) }.getOrNull()
            val places = found.orEmpty()
            mutableState.update {
                it.copy(
                    searching = false,
                    results = places,
                    message = when {
                        found == null -> SEARCH_UNREACHABLE
                        places.isEmpty() -> SEARCH_NOT_FOUND
                        else -> null
                    },
                )
            }
        }
    }

    fun choose(place: WeatherPlace) {
        viewModelScope.launch {
            repository.selectPlace(place)
            refresh()
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    /** Ask the sky once. A failure leaves the cache on screen and says so in the user's words. */
    fun refresh() {
        val place = mutableState.value.preferences.place ?: return
        if (mutableState.value.refreshing) return
        mutableState.update { it.copy(refreshing = true, message = null) }
        viewModelScope.launch {
            val reading = runCatching { source.fetch(place) }.getOrNull()
            if (reading != null) repository.saveReading(reading)
            mutableState.update { it.copy(refreshing = false, message = if (reading == null) REFRESH_FAILED else null) }
        }
    }

    class Factory(
        private val repository: WeatherRepository,
        private val source: WeatherSource,
        private val placeSearch: WeatherPlaceSearch,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WeatherViewModel::class.java))
            return WeatherViewModel(repository, source, placeSearch) as T
        }
    }
}

/** Everything the section draws. */
data class WeatherUiState(
    val loading: Boolean = false,
    val preferences: WeatherPreferences = WeatherPreferences(),
    val cached: WeatherReading? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<WeatherPlace> = emptyList(),
    val refreshing: Boolean = false,
    val message: String? = null,
) {
    /** What the widget would draw for, or the honest answer that it has nothing to draw. */
    val placeSummary: String get() = preferences.place?.displayName ?: PLACE_NONE
}

private const val PLACE_NONE = "还没有选择城市"
private const val SEARCH_UNREACHABLE = "没能连上天气服务，这次没有查到任何结果"
private const val SEARCH_NOT_FOUND = "没有找到这个地方，换个写法试试"
private const val REFRESH_FAILED = "天气没有更新，小组件会继续显示上一次的读数"
