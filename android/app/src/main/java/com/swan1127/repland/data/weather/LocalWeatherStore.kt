package com.swan1127.repland.data.weather

import android.content.Context
import com.swan1127.repland.domain.model.WeatherPreferences
import com.swan1127.repland.domain.model.WeatherPlace
import com.swan1127.repland.domain.model.WeatherReading
import com.swan1127.repland.domain.model.WeatherSky
import com.swan1127.repland.domain.ports.WeatherRepository
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Weather preferences and the last reading, kept in the app's private preferences file.
 *
 * **Why this is not a Room table.** A table means `version 21 → 22`, and this database ships with
 * `exportSchema = false` and no `schemas/` directory — so adding a migration would first mean
 * paying off that pre-existing debt, in a change whose user-visible purpose is a line of small
 * text. [com.swan1127.repland.data.profile.LocalPersonalProfileStore] set the precedent for
 * app-private state that has no relational shape, and the widget's weather is exactly that: one
 * place, one reading, no queries.
 *
 * **Nullable numbers are stored as absent keys.** A reading whose rain chance is unknown must not
 * round-trip through an `Int` default of `0`, which would come back as a confident "降水 0%".
 * Anything that cannot be parsed back is dropped whole rather than half-restored, because a status
 * line built from half a reading says something the sky did not say.
 */
class LocalWeatherStore(
    context: Context,
    storageName: String = "weather",
) : WeatherRepository {

    init { require(storageName.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }

    private val preferences = context.applicationContext
        .getSharedPreferences(storageName, Context.MODE_PRIVATE)

    private val preferencesFlow = MutableStateFlow(readPreferences())
    private val readingFlow = MutableStateFlow(readReading())

    override fun observePreferences(): Flow<WeatherPreferences> = preferencesFlow.asStateFlow()

    override fun observeCachedReading(): Flow<WeatherReading?> = readingFlow.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        preferencesFlow.value = readPreferences()
    }

    override suspend fun selectPlace(place: WeatherPlace) {
        preferences.edit()
            .putString(KEY_PLACE_NAME, place.name)
            .putString(KEY_PLACE_ADMIN1, place.admin1)
            .putString(KEY_PLACE_LAT, place.latitude.toString())
            .putString(KEY_PLACE_LON, place.longitude.toString())
            .putString(KEY_PLACE_TZ, place.timezone)
            .apply()
        // A new city invalidates the old one's sky: keeping it would put 上海's temperature next to
        // a line that says 昆明 for however long it takes the next fetch.
        preferences.edit().remove(KEY_SKY).remove(KEY_TEMPERATURE).remove(KEY_FEELS_LIKE)
            .remove(KEY_PRECIPITATION).remove(KEY_UV).remove(KEY_OBSERVED).apply()
        readingFlow.value = null
        preferencesFlow.value = readPreferences()
    }

    override suspend fun saveReading(reading: WeatherReading) {
        preferences.edit()
            .putString(KEY_SKY, reading.sky.name)
            .putString(KEY_TEMPERATURE, reading.temperatureCelsius.toString())
            .putString(KEY_FEELS_LIKE, reading.apparentTemperatureCelsius.toString())
            .putString(KEY_PRECIPITATION, reading.precipitationChancePercent?.toString())
            .putString(KEY_UV, reading.uvIndexMax?.toString())
            .putString(KEY_OBSERVED, reading.observedAt.toString())
            .apply()
        readingFlow.value = readReading()
    }

    /**
     * Removes the city, the switch and the cached reading.
     *
     * Called from "清除本地数据". A chosen city is a fact about where the user spends their day, and
     * a reading is derived from it; leaving either behind after the wipe would make the promise in
     * `docs/PRIVACY.md` false.
     */
    suspend fun clear() {
        preferences.edit().clear().apply()
        preferencesFlow.value = WeatherPreferences()
        readingFlow.value = null
    }

    private fun readPreferences(): WeatherPreferences = WeatherPreferences(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        place = runCatching {
            val name = preferences.getString(KEY_PLACE_NAME, null) ?: return@runCatching null
            WeatherPlace(
                name = name,
                admin1 = preferences.getString(KEY_PLACE_ADMIN1, "").orEmpty(),
                latitude = preferences.getString(KEY_PLACE_LAT, null)?.toDoubleOrNull() ?: return@runCatching null,
                longitude = preferences.getString(KEY_PLACE_LON, null)?.toDoubleOrNull() ?: return@runCatching null,
                timezone = preferences.getString(KEY_PLACE_TZ, null) ?: return@runCatching null,
            )
        }.getOrNull(),
    )

    private fun readReading(): WeatherReading? = runCatching {
        WeatherReading(
            sky = WeatherSky.valueOf(preferences.getString(KEY_SKY, null) ?: return@runCatching null),
            temperatureCelsius = preferences.getString(KEY_TEMPERATURE, null)?.toIntOrNull()
                ?: return@runCatching null,
            apparentTemperatureCelsius = preferences.getString(KEY_FEELS_LIKE, null)?.toIntOrNull()
                ?: return@runCatching null,
            precipitationChancePercent = preferences.getString(KEY_PRECIPITATION, null)?.toIntOrNull(),
            uvIndexMax = preferences.getString(KEY_UV, null)?.toDoubleOrNull(),
            observedAt = preferences.getString(KEY_OBSERVED, null)?.let(LocalDateTime::parse)
                ?: return@runCatching null,
        )
    }.getOrNull()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PLACE_NAME = "place_name"
        const val KEY_PLACE_ADMIN1 = "place_admin1"
        const val KEY_PLACE_LAT = "place_lat"
        const val KEY_PLACE_LON = "place_lon"
        const val KEY_PLACE_TZ = "place_tz"
        const val KEY_SKY = "sky"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_FEELS_LIKE = "feels_like"
        const val KEY_PRECIPITATION = "precipitation"
        const val KEY_UV = "uv"
        const val KEY_OBSERVED = "observed_at"
    }
}
