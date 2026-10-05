package com.nogirelay.app.ui

/**
 * Stable handles for UI automation (baseline profile generation). Exposed as
 * resource ids because the app root sets testTagsAsResourceId.
 */
object UiTestTags {
    const val MEMBER_INBOX = "member-inbox"
    const val MEMBER_THREAD = "member-thread"
    /** The media grid, voice list or favorites list of a conversation. */
    const val AUXILIARY_LIST = "auxiliary-list"
    const val BLOG_LIST = "blog-list"
    const val MEMBER_TIMELINE = "member-timeline"
    const val TIMELINE_MORE = "timeline-more"
    const val TIMELINE_MEDIA_ENTRY = "timeline-media-entry"
    const val TIMELINE_FAVORITES_ENTRY = "timeline-favorites-entry"
    const val BLOG_CARD = "blog-card"
    const val BLOG_DETAIL = "blog-detail"
}
