package com.swan1127.repland.data.competition

import android.content.Context
import com.swan1127.repland.domain.competition.SchoolCompetitionSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内置竞赛库加载器：assets/competitions/<school>.json。
 * 换学校 = 换种子文件；坏种子回退 null 不崩，等下一版种子修复。
 */
class CompetitionCatalogStore(private val context: Context) {

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, SchoolCompetitionSeed?>()

    suspend fun availableSeeds(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.list(DIR).orEmpty()
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .sorted()
        }.getOrDefault(emptyList())
    }

    suspend fun seed(name: String): SchoolCompetitionSeed? = mutex.withLock {
        if (cache.containsKey(name)) cache[name] else load(name).also { cache[name] = it }
    }

    suspend fun defaultSeed(): SchoolCompetitionSeed? = availableSeeds().firstOrNull()?.let { seed(it) }

    private suspend fun load(name: String): SchoolCompetitionSeed? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("$DIR/$name.json").use { input ->
                CompetitionSeedCodec.decode(input.readBytes().decodeToString())
            }
        }.getOrNull()
    }

    private companion object {
        const val DIR = "competitions"
    }
}
