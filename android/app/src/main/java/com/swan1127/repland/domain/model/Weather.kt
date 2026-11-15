package com.swan1127.repland.domain.model

import java.time.LocalDateTime

/**
 * The weather half of the widget's status line.
 *
 * **Why this is a value type and two interfaces, not a client.** The app's rule is that a network
 * signal annotates the schedule and never edits it: what is on the home screen must still be true
 * with the radio off, with the endpoint down, and with the feature switched off. So the domain
 * holds only what a reading *means* — [WeatherReading], [WeatherPlace] — and asks for it through
 * [WeatherSource] and [WeatherPlaceSearch], both of which have a `NoOp` that answers "I don't
 * know". The one class that talks to the sky lives in `src/remote`, where the manifest carries
 * `INTERNET`; `verifyOfflineMvpBoundary` keeps it out of `src/main`, the same arrangement the AI
 * advisors use.
 *
 * **Why nothing here mentions a provider.** `Open-Meteo` is named in `src/remote` only. A domain
 * model that carried query-parameter names could not be reused when that service changes terms,
 * and — more practically — would make the honest answer to "what does this app send to a third
 * party?" a reading of the vendor's documentation instead of of this file.
 */

/**
 * A place the user chose by typing its name.
 *
 * Chosen, never detected: this app does not request a location permission, so a coordinate pair
 * only ever arrives here after the user picked it out of a search result. [admin1] is kept because
 * the geocoder returns several places under one name — 上海 alone yields the municipality, a
 * village in 绍兴 and one in 丽江 — and the disambiguation is the difference between the user's
 * weather and somebody else's.
 */
data class WeatherPlace(
    val name: String,
    val admin1: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
) {
    init {
        require(name.isNotBlank()) { "a place needs a name" }
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        require(timezone.isNotBlank()) { "a place needs a timezone or every hour in it is wrong" }
    }

    /** `上海 · 上海市`, or just the name when the region adds nothing. */
    val displayName: String
        get() = if (admin1.isBlank() || admin1 == name) name else "$name · $admin1"
}

/**
 * The kind of sky, not its wording.
 *
 * A single enum is the whole vocabulary the app knows; turning one into a word is the resource
 * table's job (`widget_wx_*`), which is the same division [WidgetEmphasis] uses for the phase
 * labels. Providers disagree about how many kinds of drizzle exist — the mapping from their codes
 * to these eight lives with the adapter that reads them.
 */
enum class WeatherSky { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER, OTHER }

/**
 * What the sky looked like at [observedAt], as reported for [place].
 *
 * [observedAt] is when *this app fetched it*, not the provider's own timestamp: it is what decides
 * whether the widget says 「更新」 or 「缓存」, and a device that disagrees with the server's clock
 * should not be able to present a two-hour-old reading as current.
 *
 * The nullable fields are nullable because a forecast response really does omit them depending on
 * the parameters asked for. An unknown is not zero: `precipitationChancePercent = 0` would be a
 * claim about the sky that nobody made.
 */
data class WeatherReading(
    val sky: WeatherSky,
    val temperatureCelsius: Int,
    val apparentTemperatureCelsius: Int,
    val precipitationChancePercent: Int?,
    val uvIndexMax: Double?,
    val observedAt: LocalDateTime,
) {
    init {
        require(temperatureCelsius in -100..60) { "implausible temperature: $temperatureCelsius" }
        require(apparentTemperatureCelsius in -100..60) { "implausible feels-like: $apparentTemperatureCelsius" }
        require(precipitationChancePercent == null || precipitationChancePercent in 0..100) {
            "precipitation chance is a percentage, got $precipitationChancePercent"
        }
        require(uvIndexMax == null || uvIndexMax in 0.0..15.0) { "implausible UV index: $uvIndexMax" }
    }
}

/** The user's two weather choices: whether to show it at all, and for which place. */
data class WeatherPreferences(
    val enabled: Boolean = false,
    val place: WeatherPlace? = null,
) {
    /** Nothing short of both can produce a line: an enabled widget with no city says nothing true. */
    val canShow: Boolean get() = enabled && place != null
}

/** Asks the sky about one place. Returns null on any failure; a stale cache is the caller's answer. */
interface WeatherSource {
    suspend fun fetch(place: WeatherPlace): WeatherReading?
}

/**
 * Finds places by the name the user typed.
 *
 * `null` and the empty list are different answers, and the UI has to keep them apart: null means the
 * service could not be reached, `emptyList()` means it was reached and knows of no such place. Telling
 * someone to "换个写法" while the network is down sends them off to fix the wrong thing.
 */
interface WeatherPlaceSearch {
    suspend fun search(query: String): List<WeatherPlace>?
}

/** The release build's weather: it knows nothing, forever, without throwing. */
object NoOpWeatherSource : WeatherSource {
    override suspend fun fetch(place: WeatherPlace): WeatherReading? = null
}

object NoOpWeatherPlaceSearch : WeatherPlaceSearch {
    override suspend fun search(query: String): List<WeatherPlace>? = null
}
