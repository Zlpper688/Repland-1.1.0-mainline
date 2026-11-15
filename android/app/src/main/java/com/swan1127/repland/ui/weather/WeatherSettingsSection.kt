package com.swan1127.repland.ui.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.swan1127.repland.ui.components.DesignTextField as OutlinedTextField
import androidx.compose.material3.Surface
import com.swan1127.repland.ui.components.DesignSwitch as Switch
import androidx.compose.material3.Text
import com.swan1127.repland.ui.components.DesignTextButton as TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swan1127.repland.domain.model.WeatherPlace
import java.time.format.DateTimeFormatter

/**
 * The weather settings, as one surface on the 设置 page.
 *
 * It sits with the other "what the app shows you" switches rather than under 智能与个性化, because
 * nothing here is modelled: it is a line of text the widget can draw from a cache. The copy says so
 * twice — once about what the row cannot do (改动安排) and once about how a city comes to exist at
 * all (搜索后手动选择) — since a weather feature is exactly the kind of thing a user reasonably
 * assumes is reading their location.
 */
@Composable
fun WeatherSettingsSection(viewModel: WeatherViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled = state.preferences.enabled
    val hasPlace = state.preferences.place != null

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("桌面小组件天气", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = viewModel::setEnabled,
                    enabled = viewModel.networkAvailable,
                    modifier = Modifier.testTag("weather-enabled-switch").semantics { contentDescription = "桌面小组件天气" },
                )
            }
            if (!viewModel.networkAvailable) {
                Text(
                    "这个构建没有内置天气服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (enabled && viewModel.networkAvailable) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前城市：${state.placeSummary}", style = MaterialTheme.typography.bodyMedium)
                        state.cached?.let {
                            Text(
                                "最近读数 ${it.temperatureCelsius}° · ${it.observedAt.format(OBSERVED)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = !state.refreshing && hasPlace,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("weather-refresh"),
                    ) { Text(if (state.refreshing) "更新中…" else "更新") }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    singleLine = true,
                    label = { Text("搜索城市") },
                    supportingText = { Text("本应用不申请定位权限") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                    modifier = Modifier.fillMaxWidth().testTag("weather-city-input"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = viewModel::search,
                        enabled = !state.searching && state.query.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("weather-city-search"),
                    ) { Text(if (state.searching) "搜索中…" else "搜索") }
                }
                if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("weather-searching"))
                if (state.results.isNotEmpty()) {
                    state.results.forEachIndexed { index, place -> PlaceOption(place, index) { viewModel.choose(it) } }
                }
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = viewModel::clearMessage, modifier = Modifier.heightIn(min = 48.dp)) { Text("知道了") }
            }
        }
    }
}

/**
 * One candidate place, named by what disambiguates it rather than by its coordinates.
 *
 * A row of decimals is not a choice a person can make; the region is. Coordinates stay in the model
 * because that is what the forecast request needs, and they are what [WeatherPlace] guarantees are
 * real numbers.
 */
@Composable
private fun PlaceOption(place: WeatherPlace, index: Int, onChoose: (WeatherPlace) -> Unit) {
    TextButton(
        onClick = { onChoose(place) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("weather-place-option-$index"),
    ) {
        Column {
            Text(place.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOf(place.admin1, place.timezone).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val OBSERVED = DateTimeFormatter.ofPattern("MM-dd HH:mm")
