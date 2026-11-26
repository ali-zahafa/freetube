package com.tensei.freetube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.Locale

internal object PersonalFeed {
    private const val PREFIX = "local-interests:"

    fun standardToken(token: String?) = token?.takeUnless { it.startsWith(PREFIX) }

    fun topics(searches: List<String>, channels: List<String>): List<String> {
        val recent = searches.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        return buildList {
            for (i in 0 until maxOf(recent.size, channels.size)) {
                recent.getOrNull(i)?.let { add(it) }
                channels.getOrNull(i)?.let { add(it) }
            }
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    suspend fun page(token: String?, shorts: Boolean, excluded: Set<String>): FeedPage? {
        val offset = when {
            token == null -> 0
            token.startsWith(PREFIX) -> token.removePrefix(PREFIX).toIntOrNull() ?: return null
            else -> return null
        }
        val channels = try {
            WatchHistory.recommendationTopics()
        } catch (_: android.database.sqlite.SQLiteException) {
            emptyList()
        }
        val interests = topics(SearchHistory.queries, channels)
        val selected = interests.drop(offset).take(2)
        if (selected.isEmpty()) return null
        val watched = WatchHistory.progress.value.keys
        val lists = coroutineScope {
            selected.map { topic ->
                async {
                    try {
                        if (shorts) {
                            val request = JSONObject().put("query", "$topic #shorts")
                            InnerTube.parseShorts(InnerTube.postAny("search", request)).videos
                        } else {
                            YouTube.search(topic)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        val items = LinkedHashMap<String, VideoItem>()
        for (index in 0 until lists.maxOf { it.size }) {
            for (list in lists) {
                val item = list.getOrNull(index) ?: continue
                if (item.id !in watched && item.id !in excluded) items.putIfAbsent(item.id, item)
            }
        }
        if (items.isEmpty()) return null
        val next = offset + selected.size
        return FeedPage(items.values.toList(), PREFIX + if (next < interests.size) next.toString() else "end")
    }
}
