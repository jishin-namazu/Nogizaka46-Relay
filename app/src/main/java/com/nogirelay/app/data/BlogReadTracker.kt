package com.nogirelay.app.data

/** 进程内可见性状态，用于避免把已经打开的 BLOG 标记为未读。 */
object BlogReadTracker {
    @Volatile
    private var appVisible = false

    @Volatile
    private var openBlogId: String? = null

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    fun openBlog(blogId: String) {
        openBlogId = blogId
    }

    fun closeBlog(blogId: String) {
        if (openBlogId == blogId) openBlogId = null
    }

    fun isViewing(blogId: String): Boolean = appVisible && openBlogId == blogId
}
