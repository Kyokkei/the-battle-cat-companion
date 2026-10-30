package com.projectnyanko.companion.ui.seed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.projectnyanko.companion.data.catalog.CatalogLookup
import com.projectnyanko.companion.data.catalog.GachaCatalogRepository
import com.projectnyanko.companion.data.catalog.GachaEventSummary
import com.projectnyanko.companion.data.catalog.UnitStats
import com.projectnyanko.companion.data.catalog.UnitStatsRepository
import com.projectnyanko.companion.data.local.SeedProfileEntity
import com.projectnyanko.companion.data.local.SeedTrackerRepository
import com.projectnyanko.companion.domain.seed.CatRarity
import com.projectnyanko.companion.domain.seed.GachaPool
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.seed.ObservedRoll
import com.projectnyanko.companion.domain.seed.RolledCat
import com.projectnyanko.companion.domain.seed.SeekHit
import com.projectnyanko.companion.domain.seed.SeekOutcome
import com.projectnyanko.companion.domain.seed.SeekProgress
import com.projectnyanko.companion.domain.seed.SeedSeeker
import com.projectnyanko.companion.domain.seed.SeedTrackGraph
import com.projectnyanko.companion.domain.seed.TrackMarker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

enum class EventStatus { ONGOING, UPCOMING, PAST }

data class EventOption(
    val eventId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val guaranteedRolls: Int,
    val status: EventStatus,
) {
    val searchKey: String = "$name $eventId $startDate".lowercase(Locale.ROOT)
}

/**
 * One cat in the table.
 *
 * [jumpLabel] is the arrow the web tracker draws on rerolled and guaranteed cats ("→ 11B"), which
 * is the only thing that tells you the roll moved you to the other lane.
 */
data class TrackCell(
    val marker: TrackMarker,
    val label: String,
    val name: String,
    val rarity: CatRarity,
    val unitId: Int = -1,
    val isReroll: Boolean = false,
    val isGuaranteed: Boolean = false,
    /** Position this cat sends you to, e.g. "11B"; null for an ordinary cat. */
    val jumpTarget: String? = null,
    /** True when the jump lands on track B, which is drawn to the right. */
    val jumpRight: Boolean = false,
)

data class TrackRow(
    val sequence: Int,
    val trackA: TrackCell,
    val trackB: TrackCell,
    val rerollA: TrackCell?,
    val rerollB: TrackCell?,
    val guaranteedA: TrackCell?,
    val guaranteedB: TrackCell?,
)

/** The cat page the paw opens. */
data class StatsUiState(
    val loading: Boolean = false,
    val stats: UnitStats? = null,
)

/** Result of "can I reach this cat from here?" — the greyed-out path preview. */
data class PreviewState(
    val path: Set<TrackMarker> = emptySet(),
    val next: TrackMarker? = null,
    val unreachable: Boolean = false,
    val targetName: String = "",
)

sealed interface TrackerUiState {
    data object Idle : TrackerUiState
    data object Loading : TrackerUiState
    data class Failed(val message: String) : TrackerUiState
    data class Ready(
        val profile: SeedProfileEntity,
        val event: EventOption,
        val pool: GachaPool,
        val rows: List<TrackRow>,
        val lastRollName: String,
        val nextMarker: TrackMarker?,
        val nextLabel: String,
        val nextName: String,
        val guaranteedRolls: Int,
        val graph: SeedTrackGraph,
    ) : TrackerUiState
}

/** One cat the player typed into the seek form, before it is resolved to a pool slot. */
data class SeekRollInput(
    val unitId: Int,
    val name: String,
    val rarity: CatRarity,
)

data class SeekResultUi(
    val startingSeed: Long,
    val currentSeed: Long,
    val rolls: List<SeekRollInput>,
    val ambiguous: Boolean,
    val elapsedMillis: Long,
    val hits: List<SeekHit>,
)

const val MAX_SEEK_ROLLS = 10
const val MIN_SEEK_ROLLS = 3

data class SeekUiState(
    val event: EventOption? = null,
    val pool: GachaPool? = null,
    val rolls: List<SeekRollInput?> = List(MAX_SEEK_ROLLS) { null },
    val running: Boolean = false,
    val progress: SeekProgress? = null,
    val result: SeekResultUi? = null,
    val error: String? = null,
    val loadingPool: Boolean = false,
) {
    val filledRolls: List<SeekRollInput> get() = rolls.filterNotNull()
    val canSeek: Boolean
        get() = pool != null && filledRolls.size >= MIN_SEEK_ROLLS && !running
}

@OptIn(ExperimentalCoroutinesApi::class)
class SeedTrackerViewModel(
    private val repository: SeedTrackerRepository,
    private val catalogRepository: GachaCatalogRepository,
    private val unitStatsRepository: UnitStatsRepository,
) : ViewModel() {
    private data class UndoEntry(val seed: Long, val lastCatId: Int, val lastCatName: String)

    private val selectedId = MutableStateFlow<Long?>(null)
    private val _region = MutableStateFlow(GameRegion.EN)
    private val _events = MutableStateFlow<List<EventOption>>(emptyList())
    private val _seek = MutableStateFlow(SeekUiState())
    private val _preview = MutableStateFlow(PreviewState())
    private val _stats = MutableStateFlow<StatsUiState?>(null)
    private val _undoDepth = MutableStateFlow(0)
    private val undoStack = ArrayDeque<UndoEntry>()
    private var seekJob: Job? = null

    val region: StateFlow<GameRegion> = _region.asStateFlow()
    val events: StateFlow<List<EventOption>> = _events.asStateFlow()
    val seek: StateFlow<SeekUiState> = _seek.asStateFlow()
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()
    val stats: StateFlow<StatsUiState?> = _stats.asStateFlow()
    val undoDepth: StateFlow<Int> = _undoDepth.asStateFlow()

    val profiles: StateFlow<List<SeedProfileEntity>> = repository.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedProfile: StateFlow<SeedProfileEntity?> =
        combine(profiles, selectedId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracker: StateFlow<TrackerUiState> = combine(selectedProfile, _events) { profile, events ->
        profile to events
    }
        .mapLatest { (profile, events) ->
            if (profile == null) TrackerUiState.Idle
            else withContext(Dispatchers.Default) { buildTracker(profile, events) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackerUiState.Loading)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _events.value = loadEvents(_region.value)
        }
        viewModelScope.launch {
            val existing = repository.observeProfiles().first()
            if (existing.isNotEmpty()) selectProfile(existing.first().id)
        }
    }

    fun setRegion(region: GameRegion) {
        if (_region.value == region) return
        _region.value = region
        viewModelScope.launch(Dispatchers.IO) { _events.value = loadEvents(region) }
    }

    fun selectProfile(id: Long?) {
        selectedId.value = id
        undoStack.clear()
        _undoDepth.value = 0
        _preview.value = PreviewState()
        val profile = profiles.value.firstOrNull { it.id == id }
        if (profile != null) {
            val profileRegion = if (profile.region.equals("JP", ignoreCase = true)) {
                GameRegion.JP
            } else {
                GameRegion.EN
            }
            setRegion(profileRegion)
        }
        if (id == null) resetSeek()
    }

    fun deleteProfile(profile: SeedProfileEntity) {
        viewModelScope.launch {
            repository.deleteProfile(profile.id)
            if (selectedId.value == profile.id) selectProfile(null)
        }
    }

    /**
     * "I rolled this cat." Re-bases the table exactly like the web tracker: the seed becomes the
     * cat's own slot seed, that cat becomes the previous roll, and the table restarts at 1A.
     */
    fun advanceTo(marker: TrackMarker) {
        val state = tracker.value as? TrackerUiState.Ready ?: return
        val node = state.graph.cell(marker) ?: return
        if (node.slot == null) return
        val profile = state.profile
        undoStack.addLast(UndoEntry(profile.seed, profile.lastCatId, profile.lastCatName))
        _undoDepth.value = undoStack.size
        _preview.value = PreviewState()
        viewModelScope.launch {
            repository.updateLastRoll(profile.id, node.slotSeed, node.id, node.displayName)
        }
    }

    fun undoLastRoll() {
        val profile = selectedProfile.value ?: return
        val entry = undoStack.removeLastOrNull() ?: return
        _undoDepth.value = undoStack.size
        _preview.value = PreviewState()
        viewModelScope.launch {
            repository.updateLastRoll(profile.id, entry.seed, entry.lastCatId, entry.lastCatName)
        }
    }

    /** Clears the "previous cat" so row 1 stops rerolling; the seed itself stays put. */
    fun forgetLastRoll() {
        val profile = selectedProfile.value ?: return
        undoStack.clear()
        _undoDepth.value = 0
        _preview.value = PreviewState()
        viewModelScope.launch { repository.updateLastRoll(profile.id, profile.seed, 0, "") }
    }

    /** Highlights the route you would have to roll to reach [marker], or says it is unreachable. */
    fun previewPath(marker: TrackMarker) {
        val state = tracker.value as? TrackerUiState.Ready ?: return
        val target = state.graph.cell(marker) ?: return
        val path = state.graph.pathTo(marker)
        _preview.value = if (path == null) {
            PreviewState(unreachable = true, targetName = target.displayName)
        } else {
            PreviewState(
                path = path.map { it.marker() }.toSet(),
                next = path.lastOrNull()?.next?.marker(),
                targetName = target.displayName,
            )
        }
    }

    fun clearPreview() {
        _preview.value = PreviewState()
    }

    /** Opens the cat page for [unitId]; the stat table is read off the main thread. */
    fun showStats(unitId: Int) {
        if (unitId < 0) return
        _stats.value = StatsUiState(loading = true)
        viewModelScope.launch {
            val region = when (val ready = tracker.value) {
                is TrackerUiState.Ready -> if (ready.profile.region.equals("JP", true)) {
                    GameRegion.JP
                } else {
                    GameRegion.EN
                }
                else -> _region.value
            }
            val stats = withContext(Dispatchers.IO) { unitStatsRepository.stats(region, unitId) }
            _stats.value = StatsUiState(loading = false, stats = stats)
        }
    }

    fun hideStats() {
        _stats.value = null
    }

    fun createProfile(name: String, seed: Long, event: EventOption) {
        viewModelScope.launch {
            // One tracker per seed and banner: re-adding the same pair moves the existing one
            // instead of piling up identical rows.
            val existing = repository.observeProfiles().first()
                .firstOrNull { it.seed == seed && it.eventId == event.eventId }
            val id = if (existing != null) {
                repository.updateProfile(
                    existing.copy(
                        name = name.trim().ifBlank { existing.name },
                        lastCatId = 0,
                        lastCatName = "",
                    ),
                )
                existing.id
            } else {
                repository.saveProfile(
                    SeedProfileEntity(
                        name = name.trim().ifBlank { event.name.take(32) },
                        seed = seed,
                        region = _region.value.name,
                        eventId = event.eventId,
                    ),
                )
            }
            selectProfile(id)
        }
    }

    fun changeBanner(profile: SeedProfileEntity, event: EventOption) {
        viewModelScope.launch {
            repository.updateProfile(
                profile.copy(
                    eventId = event.eventId,
                    region = _region.value.name,
                    lastCatId = 0,
                    lastCatName = "",
                ),
            )
            undoStack.clear()
            _undoDepth.value = 0
            _preview.value = PreviewState()
        }
    }

    fun renameProfile(profile: SeedProfileEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.updateProfile(profile.copy(name = trimmed)) }
    }

    // ---------------------------------------------------------------- seed seeking

    fun openSeek() {
        _seek.value = SeekUiState(event = _seek.value.event)
    }

    fun closeSeek() {
        seekJob?.cancel()
        _seek.value = SeekUiState()
    }

    fun chooseSeekEvent(event: EventOption) {
        _seek.value = _seek.value.copy(
            event = event,
            pool = null,
            rolls = List(MAX_SEEK_ROLLS) { null },
            result = null,
            error = null,
            loadingPool = true,
        )
        viewModelScope.launch {
            val pool = withContext(Dispatchers.IO) { resolvePool(event.eventId) }
            if (_seek.value.event?.eventId != event.eventId) return@launch
            _seek.value = _seek.value.copy(
                pool = pool,
                loadingPool = false,
                error = if (pool == null) {
                    "This banner has no usable capsule pool in the bundled catalog."
                } else {
                    null
                },
            )
        }
    }

    fun setSeekRoll(index: Int, roll: SeekRollInput?) {
        val current = _seek.value
        if (index !in current.rolls.indices) return
        val rolls = current.rolls.toMutableList()
        rolls[index] = roll
        _seek.value = current.copy(rolls = rolls, result = null, error = null)
    }

    fun clearSeekRolls() {
        _seek.value = _seek.value.copy(
            rolls = List(MAX_SEEK_ROLLS) { null },
            result = null,
            error = null,
            progress = null,
        )
    }

    fun startSeek() {
        val state = _seek.value
        val pool = state.pool ?: return
        val rolls = state.filledRolls
        if (rolls.size < MIN_SEEK_ROLLS) {
            _seek.value = state.copy(error = "Enter at least $MIN_SEEK_ROLLS rolls so the seed is unique.")
            return
        }
        seekJob?.cancel()
        _seek.value = state.copy(running = true, error = null, result = null, progress = null)
        seekJob = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) {
                    runSeek(pool, rolls) { progress ->
                        _seek.value = _seek.value.copy(progress = progress)
                    }
                }
                val hit = outcome.hits.firstOrNull()
                _seek.value = if (hit == null) {
                    _seek.value.copy(
                        running = false,
                        progress = null,
                        error = notFoundMessage(outcome),
                    )
                } else {
                    _seek.value.copy(
                        running = false,
                        progress = null,
                        result = SeekResultUi(
                            startingSeed = hit.startingSeed,
                            currentSeed = hit.currentSeed,
                            rolls = rolls,
                            ambiguous = outcome.ambiguous,
                            elapsedMillis = outcome.elapsedMillis,
                            hits = outcome.hits,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                _seek.value = _seek.value.copy(running = false, progress = null)
                throw cancelled
            }
        }
    }

    fun cancelSeek() {
        seekJob?.cancel()
        _seek.value = _seek.value.copy(running = false, progress = null)
    }

    /**
     * Turns a successful seek into a tracked profile that continues *after* the entered rolls: the
     * seed becomes the post-roll seed and the last entered cat becomes the previous roll, so row 1
     * is the next cat you will actually get.
     */
    fun trackSeekResult(name: String) {
        val state = _seek.value
        val event = state.event ?: return
        val result = state.result ?: return
        val last = result.rolls.last()
        viewModelScope.launch {
            val existing = repository.observeProfiles().first()
                .firstOrNull { it.seed == result.startingSeed && it.eventId == event.eventId }
            val id = if (existing != null) {
                repository.updateProfile(
                    existing.copy(
                        name = name.trim().ifBlank { existing.name },
                        seed = result.currentSeed,
                        lastCatId = last.unitId,
                        lastCatName = last.name,
                    ),
                )
                existing.id
            } else {
                repository.saveProfile(
                    SeedProfileEntity(
                        name = name.trim().ifBlank { event.name.take(32) },
                        seed = result.currentSeed,
                        region = _region.value.name,
                        eventId = event.eventId,
                        lastCatId = last.unitId,
                        lastCatName = last.name,
                    ),
                )
            }
            closeSeek()
            selectProfile(id)
        }
    }

    private fun resetSeek() {
        _seek.value = SeekUiState()
    }

    private fun runSeek(
        pool: GachaPool,
        rolls: List<SeekRollInput>,
        onProgress: (SeekProgress) -> Unit,
    ): SeekOutcome {
        val combinations = slotCombinations(pool, rolls)
        var last: SeekOutcome? = null
        for (combination in combinations) {
            val outcome = SeedSeeker().seek(pool, combination, onProgress = onProgress)
            last = outcome
            if (outcome.hits.isNotEmpty()) return outcome
        }
        return requireNotNull(last)
    }

    /**
     * A banner can list the same unit twice inside one rarity and the player cannot tell which slot
     * they hit, so every assignment is tried. Ambiguity is rare (21 of 585 bundled pools), and the
     * list is capped so a pathological banner cannot turn one seek into hundreds.
     */
    private fun slotCombinations(
        pool: GachaPool,
        rolls: List<SeekRollInput>,
    ): List<List<ObservedRoll>> {
        val candidates = rolls.map { roll ->
            val slots = pool.slots(roll.rarity).withIndex()
                .filter { it.value.id == roll.unitId }
                .map { it.index }
            require(slots.isNotEmpty()) {
                "${roll.name} is not in this banner's ${roll.rarity.displayName} pool."
            }
            slots.map { ObservedRoll(roll.rarity, it, roll.unitId, roll.name) }
        }
        var combinations = listOf(emptyList<ObservedRoll>())
        for (options in candidates) {
            combinations = combinations
                .flatMap { prefix -> options.map { option -> prefix + option } }
                .take(MAX_SLOT_COMBINATIONS)
        }
        return combinations
    }

    private fun notFoundMessage(outcome: SeekOutcome): String = buildString {
        append("Nothing matched in all 4,294,967,296 seeds (${outcome.elapsedMillis / 1000.0}s).")
        append("\n\nCheck, in this order:")
        append("\n• The region and banner you rolled on — the pool has to match exactly.")
        append("\n• The order and identity of every cat.")
        append("\n• For an 11-roll guarantee, leave the guaranteed uber out.")
        append("\n• Collab banners that repeat a cat may need a different banner entry.")
    }

    private fun resolvePool(eventId: String): GachaPool? =
        when (val lookup = catalogRepository.resolve(_region.value, eventId)) {
            is CatalogLookup.Available -> lookup.pool
            is CatalogLookup.Unavailable -> null
        }

    private fun loadEvents(region: GameRegion): List<EventOption> {
        val today = todayIso()
        return catalogRepository.events(region)
            .map { it.toOption(today) }
            .sortedWith { a, b ->
                when {
                    a.status != b.status -> a.status.ordinal - b.status.ordinal
                    a.status == EventStatus.PAST -> b.startDate.compareTo(a.startDate)
                    a.status == EventStatus.UPCOMING -> a.startDate.compareTo(b.startDate)
                    else -> a.endDate.compareTo(b.endDate)
                }
            }
    }

    private fun GachaEventSummary.toOption(today: String): EventOption {
        val status = when {
            endDate.isNotEmpty() && endDate < today -> EventStatus.PAST
            startDate > today -> EventStatus.UPCOMING
            else -> EventStatus.ONGOING
        }
        return EventOption(
            eventId = eventId,
            name = name,
            startDate = startDate,
            endDate = endDate,
            guaranteedRolls = guaranteedRolls,
            status = status,
        )
    }

    private fun todayIso(): String {
        val calendar = Calendar.getInstance()
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun buildTracker(profile: SeedProfileEntity, events: List<EventOption>): TrackerUiState {
        val region = if (profile.region.equals("JP", ignoreCase = true)) GameRegion.JP else GameRegion.EN
        val pool = when (val lookup = catalogRepository.resolve(region, profile.eventId)) {
            is CatalogLookup.Available -> lookup.pool
            is CatalogLookup.Unavailable -> return TrackerUiState.Failed(lookup.reason)
        }
        val event = events.firstOrNull { it.eventId == profile.eventId }
            ?: EventOption(
                eventId = profile.eventId,
                name = pool.eventName,
                startDate = "",
                endDate = "",
                guaranteedRolls = pool.guaranteedRolls,
                status = EventStatus.ONGOING,
            )

        val graph = SeedTrackGraph(pool, profile.seed, TABLE_ROWS + 12, profile.lastCatId)
        val rows = ArrayList<TrackRow>(TABLE_ROWS)
        for (sequence in 1..TABLE_ROWS) {
            val pair = graph.row(sequence) ?: break
            rows += TrackRow(
                sequence = sequence,
                trackA = pair.trackA.toCell(),
                trackB = pair.trackB.toCell(),
                rerollA = pair.trackA.rerolled?.toCell(withJump = true),
                rerollB = pair.trackB.rerolled?.toCell(withJump = true),
                guaranteedA = pair.trackA.guaranteedResult?.toCell(withJump = true),
                guaranteedB = pair.trackB.guaranteedResult?.toCell(withJump = true),
            )
        }

        val next = graph.nextNode()
        return TrackerUiState.Ready(
            profile = profile,
            event = event,
            pool = pool,
            rows = rows,
            lastRollName = when {
                profile.lastCatId == 0 -> ""
                profile.lastCatName.isNotBlank() -> profile.lastCatName
                else -> "unit ${profile.lastCatId}"
            },
            nextMarker = next?.marker(),
            nextLabel = next?.displayPosition.orEmpty(),
            nextName = next?.displayName.orEmpty(),
            guaranteedRolls = pool.guaranteedRolls,
            graph = graph,
        )
    }

    private fun RolledCat.toCell(withJump: Boolean = false): TrackCell = TrackCell(
        marker = marker(),
        label = displayPosition,
        name = displayName,
        rarity = rarity,
        unitId = id,
        isReroll = 'R' in extraLabel,
        isGuaranteed = isGuaranteed,
        // The web tracker puts a right arrow after the name and a left arrow before it, so the
        // arrow always points at the column you land in.
        jumpTarget = if (!withJump) null else next?.displayPosition,
        jumpRight = withJump && next?.track == 1,
    )

    companion object {
        private const val MAX_SLOT_COMBINATIONS = 16
        private const val TABLE_ROWS = 100

        fun factory(
            repository: SeedTrackerRepository,
            catalogRepository: GachaCatalogRepository,
            unitStatsRepository: UnitStatsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SeedTrackerViewModel::class.java))
                return SeedTrackerViewModel(repository, catalogRepository, unitStatsRepository) as T
            }
        }
    }
}
