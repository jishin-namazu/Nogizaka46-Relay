package com.nogirelay.app.data

/** Process-local visibility used to avoid marking a message unread while its thread is on screen. */
object MessageReadTracker {
    @Volatile
    private var appVisible = false

    @Volatile
    private var openMemberKey: String? = null

    @Volatile
    private var isViewingLatest = true

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
    }

    fun openMember(memberKey: String, viewingLatest: Boolean = true) {
        openMemberKey = memberKey
        isViewingLatest = viewingLatest
    }

    fun updateViewingLatest(viewingLatest: Boolean) {
        isViewingLatest = viewingLatest
    }

    fun closeMember(memberKey: String) {
        if (openMemberKey == memberKey) {
            openMemberKey = null
            isViewingLatest = true
        }
    }

    fun isViewing(memberKey: String): Boolean = appVisible && openMemberKey == memberKey && isViewingLatest
}
