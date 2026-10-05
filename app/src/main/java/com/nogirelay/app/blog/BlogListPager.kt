package com.nogirelay.app.blog

import com.nogirelay.app.data.repository.BlogRowChanges
import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.data.isRealBlogImageUrl
import com.nogirelay.app.data.readDatabase
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.primeCachedImageAspectRatios
import com.nogirelay.app.ui.searchSnippets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Rows read per database round trip; the list prefetches one chunk ahead. */
internal const val BLOG_CHUNK_SIZE = 30

/** Everything that selects and orders the blog list. */
internal data class BlogListQuery(
    val memberIds: Set<String>? = null,
    val searchQuery: String = "",
    val oldestFirst: Boolean = false,
    val timeFilter: TimeFilter = TimeFilter(),
    val translationEnabled: Boolean = false,
) {
    val isDefault: Boolean
        get() = memberIds == null && searchQuery.isBlank() && !oldestFirst && !timeFilter.isActive
}

/** A publishing month in list order: its label and the index of its first post. */
@Immutable
internal data class BlogMonthSlot(val label: String, val startIndex: Int, val count: Int)

/**
 * One immutable view of the list. A new query, a data refresh and every
 * loaded chunk each publish a whole new snapshot, so the list never shows a
 * mix of two queries.
 */
@Immutable
internal data class BlogListSnapshot(
    val query: BlogListQuery? = null,
    val totalCount: Int = 0,
    val matchingCount: Int = 0,
    val months: List<BlogMonthSlot> = emptyList(),
    /** Loaded chunks; a null entry is a row shown elsewhere in a stale chunk. */
    val chunks: Map<Int, List<BlogSummary?>> = emptyMap(),
    val previews: Map<String, List<BlogSearchPreview>> = emptyMap(),
    /** Bumped when the query changes so cards crossfade instead of moving. */
    val generation: Int = 0,
    val loaded: Boolean = false,
) {
    fun item(index: Int): BlogSummary? =
        chunks[index / BLOG_CHUNK_SIZE]?.getOrNull(index % BLOG_CHUNK_SIZE)

    /** The month containing list [index]. */
    fun monthAt(index: Int): BlogMonthSlot? = months.slotAt(index)
}

/** The month slot containing list [index], by binary search over month starts. */
internal fun List<BlogMonthSlot>.slotAt(index: Int): BlogMonthSlot? {
    if (isEmpty()) return null
    var low = 0
    var high = lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (this[mid].startIndex <= index) low = mid else high = mid - 1
    }
    return this[low]
}

/**
 * Infinite blog list backed by offset chunks. Only chunks near the viewport
 * are read; a new query or data change swaps in counts, the month index and
 * the chunks around the anchor in one step.
 */
@Stable
internal class BlogListPager(
    private val context: Context,
    private val scope: CoroutineScope,
    initial: BlogListSnapshot?,
) {
    var snapshot by mutableStateOf(initial ?: BlogListSnapshot())
        private set

    private val chunkJobs = mutableMapOf<Int, Job>()
    private var epoch = 0

    /** Row and list revisions the current snapshot reflects. */
    private var rowVersion = -1L
    private var listRevision = -1L

    /**
     * Loads [query] (or reloads it after a data change) with the chunks within
     * [radius] of [anchorIndex]'s chunk. Suspends until the new snapshot is live.
     */
    suspend fun load(query: BlogListQuery, anchorIndex: Int, radius: Int = 1, changes: BlogRowChanges? = null) {
        val myEpoch = ++epoch
        changes?.let {
            rowVersion = it.version
            listRevision = it.listRevision
        }
        chunkJobs.values.forEach(Job::cancel)
        chunkJobs.clear()
        val previous = snapshot
        val anchorChunk = (anchorIndex.coerceAtLeast(0)) / BLOG_CHUNK_SIZE
        val loaded = readDatabase { cancellation ->
            // One grouped pass gives both the month index and the match count;
            // the unfiltered total only needs a separate (indexed) count.
            val monthCounts = AppGraph.blogs.blogMonthCounts(
                memberIds = query.memberIds,
                searchQuery = query.searchQuery,
                oldestFirst = query.oldestFirst,
                startMillis = query.timeFilter.startMillis,
                endMillisExclusive = query.timeFilter.endMillisExclusive,
                cancellationSignal = cancellation,
            )
            val months = monthSlots(monthCounts)
            val matching = monthCounts.sumOf { it.second }
            val total = if (query.isDefault) matching else AppGraph.blogs.countBlogs(cancellationSignal = cancellation)
            val lastChunk = ((matching - 1) / BLOG_CHUNK_SIZE).coerceAtLeast(0)
            val wanted = (anchorChunk - radius..anchorChunk + radius).filter { it in 0..lastChunk }
            val chunks = wanted.associateWith { readChunk(query, it, cancellation) }
            LoadedPage(total, matching, months, chunks)
        }
        primeImages(loaded.chunks.values.flatMap { it.posts })
        if (myEpoch != epoch) return
        snapshot = BlogListSnapshot(
            query = query,
            totalCount = loaded.total,
            matchingCount = loaded.matching,
            months = loaded.months,
            chunks = dedupe(loaded.chunks.mapValues { it.value.posts }),
            previews = loaded.chunks.values.fold(emptyMap()) { acc, chunk -> acc + chunk.previews },
            generation = if (previous.query == null || previous.query == query) previous.generation else previous.generation + 1,
            loaded = true,
        )
        if (query.isDefault && anchorChunk <= 1) BlogPrewarmer.cachedSnapshot = snapshot
        preloadImages(loaded.chunks.values.flatMap { it.posts })
    }

    /**
     * Re-reads just the loaded rows named by [changes] (read marks, new
     * translations) and swaps them in place. Returns true when the list must
     * reload instead: too many rows changed, or a search could now match
     * differently. A structural change is left to the list revision reload.
     */
    suspend fun applyRowChanges(changes: BlogRowChanges): Boolean {
        if (changes.version <= rowVersion) return false
        val current = snapshot
        val query = current.query ?: return false
        if (changes.listRevision != listRevision) return false
        val ids = changes.changedSince(rowVersion) ?: return true
        if (query.searchQuery.isNotBlank()) return true
        rowVersion = changes.version
        val shown = current.chunks.values.asSequence().flatten().filterNotNull().map(BlogSummary::id).toSet()
        val wanted = ids.filter(shown::contains)
        if (wanted.isEmpty()) return false
        val fresh = readDatabase { AppGraph.blogs.blogSummariesByIds(wanted) }.associateBy(BlogSummary::id)
        val latest = snapshot
        if (latest.query != query) return false
        snapshot = latest.copy(
            chunks = latest.chunks.mapValues { (_, rows) -> rows.map { row -> row?.let { fresh[it.id] ?: it } } },
        )
        return false
    }

    /** Starts reading any missing chunk overlapping list indices [first]..[last]. */
    fun ensureLoaded(first: Int, last: Int) {
        val current = snapshot
        val query = current.query ?: return
        if (!current.loaded || current.matchingCount == 0) return
        val lastChunk = (current.matchingCount - 1) / BLOG_CHUNK_SIZE
        val from = (first / BLOG_CHUNK_SIZE - 1).coerceAtLeast(0)
        val to = (last / BLOG_CHUNK_SIZE + 1).coerceAtMost(lastChunk)
        for (chunk in from..to) {
            if (chunk in current.chunks || chunkJobs[chunk]?.isActive == true) continue
            val myEpoch = epoch
            chunkJobs[chunk] = scope.launch {
                val loaded = try {
                    readDatabase { cancellation -> readChunk(query, chunk, cancellation) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Leave the slot as a placeholder; the next scroll retries it.
                    Log.w("BlogListPager", "Blog chunk $chunk failed to load", error)
                    chunkJobs.remove(chunk)
                    return@launch
                }
                primeImages(loaded.posts)
                if (myEpoch != epoch || snapshot.query != query) return@launch
                val latest = snapshot
                snapshot = latest.copy(
                    chunks = dedupe(latest.chunks + (chunk to loaded.posts), fresh = chunk),
                    previews = latest.previews + loaded.previews,
                )
                chunkJobs.remove(chunk)
                preloadImages(loaded.posts)
            }
        }
    }

    private fun readChunk(query: BlogListQuery, chunk: Int, cancellation: CancellationSignal): LoadedChunk {
        val posts = AppGraph.blogs.blogSummaries(
            memberIds = query.memberIds,
            searchQuery = query.searchQuery,
            oldestFirst = query.oldestFirst,
            startMillis = query.timeFilter.startMillis,
            endMillisExclusive = query.timeFilter.endMillisExclusive,
            limit = BLOG_CHUNK_SIZE,
            offset = chunk * BLOG_CHUNK_SIZE,
            cancellationSignal = cancellation,
        )
        val searchQuery = query.searchQuery
        val previews = if (searchQuery.isBlank() || posts.isEmpty()) {
            emptyMap()
        } else {
            AppGraph.blogs.blogSearchSources(posts.map(BlogSummary::id))
                .mapNotNull { source ->
                    cancellation.throwIfCanceled()
                    val excerpts = buildList {
                        val originalSnippets = searchSnippets(
                            BlogTextCache.parse(source.id, source.bodyHtml).plainText,
                            searchQuery,
                        )
                        originalSnippets.forEachIndexed { index, snippet ->
                            val label = if (originalSnippets.size > 1) "原文 ${index + 1}" else "原文"
                            add(BlogSearchPreview(label, snippet))
                        }
                        if (query.translationEnabled) {
                            val translatedSnippets = searchSnippets(translatedBlogText(source.translation), searchQuery)
                            translatedSnippets.forEachIndexed { index, snippet ->
                                val label = if (translatedSnippets.size > 1) "译文 ${index + 1}" else "译文"
                                add(BlogSearchPreview(label, snippet))
                            }
                        }
                    }
                    excerpts.takeIf { it.isNotEmpty() }?.let { source.id to it }
                }
                .toMap()
        }
        return LoadedChunk(posts, previews)
    }

    /** Read cached image sizes first so covers open at their final height. */
    private suspend fun primeImages(posts: List<BlogSummary>) {
        primeCachedImageAspectRatios(context, posts.mapNotNull { it.imageUrl?.takeIf(::isRealBlogImageUrl) })
    }

    private fun preloadImages(posts: List<BlogSummary>) {
        val urls = posts.mapNotNull { it.imageUrl?.takeIf(::isRealBlogImageUrl) }
        if (urls.isEmpty()) return
        scope.launch { BlogMediaDownloader.preloadImages(context, urls, limit = urls.size) }
    }

    private class LoadedChunk(
        val posts: List<BlogSummary>,
        val previews: Map<String, List<BlogSearchPreview>>,
    )

    private class LoadedPage(
        val total: Int,
        val matching: Int,
        val months: List<BlogMonthSlot>,
        val chunks: Map<Int, LoadedChunk>,
    )

    private companion object {
        /**
         * Lazy keys must be unique. A chunk read after the data shifted can
         * repeat a post that another chunk already shows; the newer copy
         * ([fresh]) yields, and the slot renders as a placeholder until the
         * next refresh.
         */
        fun dedupe(chunks: Map<Int, List<BlogSummary?>>, fresh: Int? = null): Map<Int, List<BlogSummary?>> {
            val seen = HashSet<String>()
            val order = chunks.keys.sortedBy { if (it == fresh) Int.MAX_VALUE else it }
            val result = HashMap<Int, List<BlogSummary?>>(chunks.size)
            for (chunk in order) {
                result[chunk] = chunks.getValue(chunk).map { post -> post?.takeIf { seen.add(it.id) } }
            }
            return result
        }

        fun monthSlots(counts: List<Pair<String, Int>>): List<BlogMonthSlot> {
            var start = 0
            return counts.map { (month, count) ->
                val label = month.split('-').let { parts ->
                    val year = parts.getOrNull(0)
                    val monthNumber = parts.getOrNull(1)?.toIntOrNull()
                    if (year != null && monthNumber != null) "${year}年${monthNumber}月" else "日期未知"
                }
                BlogMonthSlot(label, start, count).also { start += count }
            }
        }
    }
}
