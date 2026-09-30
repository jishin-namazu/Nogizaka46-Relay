package com.nogirelay.app.data

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
