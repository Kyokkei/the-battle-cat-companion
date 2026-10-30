package com.projectnyanko.companion.data.catalog

import com.projectnyanko.companion.domain.seed.CatRarity
import com.projectnyanko.companion.domain.seed.CatSlot
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.seed.GachaPool
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Pure parser/validator shared by Android asset loading and local catalog parity tests. */
object GachaCatalogParser {
    fun parse(
        region: GameRegion,
        eventBytes: ByteArray,
        poolBytes: ByteArray,
        unitBytes: ByteArray,
    ): ParsedRegionCatalog {
        val eventsJson = JSONArray(eventBytes.toString(Charsets.UTF_8))
        val poolsJson = JSONObject(poolBytes.toString(Charsets.UTF_8))
        val unitsJson = JSONArray(unitBytes.toString(Charsets.UTF_8))
        val eventById = LinkedHashMap<String, JSONObject>()
        val events = buildList {
            for (index in 0 until eventsJson.length()) {
                val item = eventsJson.getJSONObject(index)
                val eventId = item.optString("event_id")
                val poolId = item.optInt("pool_id", -1)
                if (eventId.isBlank() || poolId < 0) continue
                require(eventById.put(eventId, item) == null) { "Duplicate event ID $eventId in ${region.label} source." }
                add(
                    GachaEventSummary(
                        eventId = eventId,
                        name = item.optString("name", eventId),
                        startDate = item.optString("start"),
                        endDate = item.optString("end"),
                        poolId = poolId,
                        guaranteedRolls = when {
                            item.optBoolean("guaranteed") -> 11
                            item.optBoolean("step_up") -> 15
                            else -> 0
                        },
                    ),
                )
            }
        }
        require(events.isNotEmpty()) { "No events in the ${region.label} data." }
        val units = buildMap {
            for (index in 0 until unitsJson.length()) {
                val item = unitsJson.getJSONObject(index)
                val id = item.optInt("id", Int.MIN_VALUE)
                if (id != Int.MIN_VALUE) require(put(id, item) == null) { "Duplicate unit ID $id." }
            }
        }
        require(units.isNotEmpty()) { "No units in the ${region.label} data." }
        val metadata = CatalogMetadata(
            region = region,
            source = "https://github.com/IliyanGavrilov/NekoOptimizer/tree/main/neko/data/${region.name.lowercase()}",
            sourceCommit = "not recorded in supplied folder",
            captureDate = "repository push observed 2026-10-27; file snapshot commit unrecorded",
            latestScheduledEventStart = events.maxOfOrNull { it.startDate }.orEmpty(),
            eventsSha256 = sha256(eventBytes),
            poolsSha256 = sha256(poolBytes),
            unitsSha256 = sha256(unitBytes),
            eventCount = events.size,
            poolCount = poolsJson.length(),
            unitCount = units.size,
            rightsNotice = "No repository license was present; attribution recorded, redistribution rights unverified.",
        )
        return ParsedRegionCatalog(region, events, eventById, poolsJson, units, metadata)
    }

    private fun parseRarity(value: String): CatRarity? = when (value.trim().lowercase()) {
        "rare" -> CatRarity.RARE
        "super rare" -> CatRarity.SUPER_RARE
        "uber super rare", "uber rare" -> CatRarity.UBER_RARE
        "legend rare" -> CatRarity.LEGEND_RARE
        else -> null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    class ParsedRegionCatalog internal constructor(
        val region: GameRegion,
        val events: List<GachaEventSummary>,
        private val eventById: Map<String, JSONObject>,
        private val pools: JSONObject,
        private val units: Map<Int, JSONObject>,
        val metadata: CatalogMetadata,
    ) {
        fun resolve(eventId: String): CatalogLookup {
            val event = events.firstOrNull { it.eventId == eventId }
                ?: return CatalogLookup.Unavailable("Event $eventId is not in the ${region.label} catalog.")
            val eventJson = eventById[eventId]
                ?: return CatalogLookup.Unavailable("Event metadata for $eventId is missing.")
            val ids = pools.optJSONArray(event.poolId.toString())
                ?: return CatalogLookup.Unavailable("Pool ${event.poolId} is missing from the ${region.label} catalog.")
            val slots = CatRarity.entries.associateWith { mutableListOf<CatSlot>() }.toMutableMap()
            for (index in 0 until ids.length()) {
                val unitId = ids.optInt(index, Int.MIN_VALUE)
                val unit = units[unitId]
                    ?: return CatalogLookup.Unavailable("Pool ${event.poolId} references missing unit ID $unitId.")
                val rarity = parseRarity(unit.optString("rarity"))
                    ?: return CatalogLookup.Unavailable("Unit $unitId has unsupported rarity '${unit.optString("rarity")}.'")
                slots.getValue(rarity) += CatSlot(unitId, unit.optString("name"), rarity)
            }
            if (slots.values.all { it.isEmpty() }) {
                return CatalogLookup.Unavailable("Pool ${event.poolId} has no resolvable units.")
            }

            val rare = eventJson.optInt("rare", -1)
            val supa = eventJson.optInt("supa", -1)
            val uber = eventJson.optInt("uber", -1)
            val legend = eventJson.optInt("legend", -1)
            if (rare < 0 || supa < 0 || uber < 0 || legend < 0 || rare + supa + uber + legend != 10_000) {
                return CatalogLookup.Unavailable("Event $eventId rarity rates do not total 10,000.")
            }
            val pool = try {
                GachaPool(
                    eventId = event.eventId,
                    eventName = event.name,
                    region = region,
                    rareWeight = rare,
                    superRareWeight = supa,
                    uberRareWeight = uber,
                    slotsByRarity = slots.mapValues { it.value.toList() },
                    guaranteedRolls = event.guaranteedRolls,
                    catalogVersion = metadata.eventsSha256.take(12),
                )
            } catch (error: IllegalArgumentException) {
                return CatalogLookup.Unavailable("Event $eventId pool metadata is invalid: ${error.message}")
            }
            return CatalogLookup.Available(pool, event, metadata)
        }
    }
}
