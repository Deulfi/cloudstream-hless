package com.lagradost.hless

import com.lagradost.macos.models.StreamLinkDto
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One engine link with what a probe found out about it (5b). */
data class Item(val link: StreamLinkDto, val facts: LinkFacts? = null)

private data class Group(val source: String, val host: String)

private fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host.orEmpty()

/**
 * Folds the variant playlists of an HLS master into the master (5b).
 *
 * Extensions often return a master playlist and, again, each of its variants
 * as separate links. Within one provider's links, a group is the HLS links
 * with the same source name and host. If a probe confirmed a master in the
 * group, the group keeps that master (the one with the most variants) and
 * drops its other masters, its media playlists, and unprobed links whose
 * height is one of the master's. A group without a confirmed master is left
 * alone: showing duplicates beats hiding a working link.
 *
 * [keepVariants] switches folding off.
 */
fun fold(items: List<Item>, keepVariants: Boolean): List<Item> {
    if (keepVariants) return items
    val dropped = HashSet<Int>()
    val groups = items.withIndex()
        .filter { it.value.link.type == "M3U8" }
        .groupBy { Group(it.value.link.source, hostOf(it.value.link.url)) }
    for (members in groups.values) {
        val masters = members.filter { it.value.facts?.kind == Kind.MASTER }
        if (masters.isEmpty()) continue
        val keep = masters.maxByOrNull { it.value.facts!!.heights.size }!!
        val heights = keep.value.facts!!.heights
        for (member in members) {
            if (member.index == keep.index) continue
            val facts = member.value.facts
            val drop = when (facts?.kind) {
                Kind.MASTER, Kind.MEDIA -> true
                null -> knownHeight(member.value.link.quality)?.let { it in heights } == true
                else -> false
            }
            if (drop) dropped += member.index
        }
    }
    return items.filterIndexed { index, _ -> index !in dropped }
}
