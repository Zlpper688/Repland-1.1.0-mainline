package com.swan1127.repland.domain.ports

import com.swan1127.repland.domain.model.WeatherPreferences
import com.swan1127.repland.domain.model.WeatherPlace
import com.swan1127.repland.domain.model.WeatherReading
import kotlinx.coroutines.flow.Flow

/**
 * The widget's weather state, on device.
 *
 * Preferences and the last reading live behind one port because they are one question — "what
 * should the widget's status line say about the sky" — and splitting them into two ports would
 * only mean the widget has to reach across the app twice to answer it. [ReminderSettingsRepository]
 * is the single-row precedent for the preferences half.
 *
 * **There is no network in this interface.** Fetching is [com.swan1127.repland.domain.model.WeatherSource],
 * which `src/main` implements only as a no-op. A repository that could also fetch would put
 * `INTERNET` on the critical path of reading a value the user asked for an hour ago.
 */
interface WeatherRepository {

    fun observePreferences(): Flow<WeatherPreferences>

    /** The gate. Off, the widget's status row is hidden rather than filled with a placeholder. */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Records the place the user picked.
     *
     * Saving the coordinate pair rather than re-resolving the name on every draw is deliberate: a
     * widget update that needed the geocoder would fail whenever the radio does, and the city the
     * user chose once would silently become a village in another province that a name-only lookup
     * resolves to.
     */
    suspend fun selectPlace(place: WeatherPlace)

    /** Null until the first successful reading. Null renders as "no row", never as "0°". */
    fun observeCachedReading(): Flow<WeatherReading?>

    suspend fun saveReading(reading: WeatherReading)
}
