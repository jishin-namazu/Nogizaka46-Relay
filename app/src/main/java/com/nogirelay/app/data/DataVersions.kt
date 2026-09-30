package com.nogirelay.app.data

enum class DataChange { ALL, CONTENT, MESSAGES, BLOGS, MESSAGE_ROWS, BLOG_ROWS, MESSAGE_READ, BLOG_READ, SETTINGS }

data class DataVersions(
    val revision: Long = 0,
    val messages: Long = 0,
    val blogs: Long = 0,
    val settings: Long = 0,
    val messageStructure: Long = 0,
    val blogStructure: Long = 0,
    val blogContent: Long = 0,
    val unread: Long = 0,
    val messagePatchFloor: Long = 0,
    val messagePatches: Map<String, Long> = emptyMap(),
) {
    fun changed(change: DataChange, ids: Set<String> = emptySet()): DataVersions {
        val next = revision + 1
        val messageChange = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.MESSAGES, DataChange.MESSAGE_ROWS, DataChange.MESSAGE_READ)
        val blogChange = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.BLOGS, DataChange.BLOG_ROWS, DataChange.BLOG_READ)
        val structure = change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.MESSAGES)
        val rowChange = change == DataChange.MESSAGE_ROWS || change == DataChange.MESSAGE_READ
        val patches = if (structure || rowChange && ids.isEmpty()) emptyMap() else if (rowChange) {
            messagePatches + ids.associateWith { next }
        } else messagePatches
        val retained = patches.entries.sortedByDescending { it.value }.take(256).associate { it.toPair() }
        val floor = if (structure || rowChange && ids.isEmpty()) next else {
            maxOf(messagePatchFloor, patches.filterKeys { it !in retained }.values.maxOrNull() ?: 0)
        }
        return copy(
            revision = next,
            messages = if (messageChange) next else messages,
            blogs = if (blogChange) next else blogs,
            settings = if (change == DataChange.ALL || change == DataChange.SETTINGS) next else settings,
            messageStructure = if (structure) next else messageStructure,
            blogStructure = if (change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.BLOGS)) next else blogStructure,
            blogContent = if (blogChange && change != DataChange.BLOG_READ) next else blogContent,
            unread = if (change in setOf(DataChange.ALL, DataChange.CONTENT, DataChange.MESSAGES, DataChange.BLOGS, DataChange.MESSAGE_READ, DataChange.BLOG_READ)) next else unread,
            messagePatchFloor = floor,
            messagePatches = retained,
        )
    }

    fun messageIdsSince(version: Long): Set<String>? =
        if (version < messageStructure || version < messagePatchFloor) null
        else messagePatches.filterValues { it > version }.keys
}
