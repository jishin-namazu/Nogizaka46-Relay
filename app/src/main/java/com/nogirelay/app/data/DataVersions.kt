package com.nogirelay.app.data

enum class DataChange { ALL, CONTENT, MESSAGES, BLOGS, MESSAGE_ROWS, BLOG_ROWS, MESSAGE_READ, BLOG_READ, SETTINGS }

/**
 * Revision counters per kind of data. Structural changes (new or removed
 * rows) bump a structure revision and force readers to reload; row changes
 * with ids are kept as patches, so a reader can re-read just those rows.
 */
data class DataVersions(
    val revision: Long = 0,
    val messages: Long = 0,
    /** Blog list structure: new posts, member directory, bulk edits. */
    val blogs: Long = 0,
    val settings: Long = 0,
    val messageStructure: Long = 0,
    val blogStructure: Long = 0,
    val blogContent: Long = 0,
    val unread: Long = 0,
    val messagePatchFloor: Long = 0,
    val messagePatches: Map<String, Long> = emptyMap(),
    /** Any blog row change, structural or not; [blogPatches] name the rows. */
    val blogRows: Long = 0,
    val blogPatchFloor: Long = 0,
    val blogPatches: Map<String, Long> = emptyMap(),
) {
    fun changed(change: DataChange, ids: Set<String> = emptySet()): DataVersions {
        val next = revision + 1
        val messageChange = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.MESSAGES, DataChange.MESSAGE_ROWS, DataChange.MESSAGE_READ)
        val blogChange = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.BLOGS, DataChange.BLOG_ROWS, DataChange.BLOG_READ)
        val structure = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.MESSAGES)
        val rowChange = change == DataChange.MESSAGE_ROWS || change == DataChange.MESSAGE_READ
        val messages = patched(structure, rowChange, ids, next, messagePatches, messagePatchFloor)

        val blogStructural = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.BLOGS)
        val blogRowChange = change == DataChange.BLOG_ROWS || change == DataChange.BLOG_READ
        val blogsPatched = patched(blogStructural, blogRowChange, ids, next, blogPatches, blogPatchFloor)
        // Row edits without ids (e.g. "retranslate everything") still reload the list.
        val blogListReload = blogStructural || blogRowChange && ids.isEmpty()

        return copy(
            revision = next,
            messages = if (messageChange) next else this.messages,
            blogs = if (blogListReload) next else blogs,
            settings = if (change == DataChange.ALL || change == DataChange.SETTINGS) next else settings,
            messageStructure = if (structure) next else messageStructure,
            blogStructure = if (blogStructural) next else blogStructure,
            blogContent = if (blogChange && change != DataChange.BLOG_READ) next else blogContent,
            unread = if (change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.MESSAGES, DataChange.BLOGS, DataChange.MESSAGE_READ, DataChange.BLOG_READ)) next else unread,
            messagePatchFloor = messages.floor,
            messagePatches = messages.patches,
            blogRows = if (blogChange) next else blogRows,
            blogPatchFloor = blogsPatched.floor,
            blogPatches = blogsPatched.patches,
        )
    }

    fun messageIdsSince(version: Long): Set<String>? =
        if (version < messageStructure || version < messagePatchFloor) null
        else messagePatches.filterValues { it > version }.keys

    /** Blog rows changed after [version], or null when the list must reload. */
    fun blogIdsSince(version: Long): Set<String>? =
        if (version < blogs || version < blogPatchFloor) null
        else blogPatches.filterValues { it > version }.keys

    private class Patched(val floor: Long, val patches: Map<String, Long>)

    private fun patched(
        structure: Boolean,
        rowChange: Boolean,
        ids: Set<String>,
        next: Long,
        current: Map<String, Long>,
        currentFloor: Long,
    ): Patched {
        val reset = structure || rowChange && ids.isEmpty()
        val patches = when {
            reset -> emptyMap()
            rowChange -> current + ids.associateWith { next }
            else -> current
        }
        val retained = patches.entries.sortedByDescending { it.value }.take(MAX_PATCHES).associate { it.toPair() }
        val floor = if (reset) next else {
            maxOf(currentFloor, patches.filterKeys { it !in retained }.values.maxOrNull() ?: 0)
        }
        return Patched(floor, retained)
    }

    private companion object {
        const val MAX_PATCHES = 256
    }
}
