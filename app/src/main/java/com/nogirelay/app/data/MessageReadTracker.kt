package com.nogirelay.app.data

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

    fun closeMember(memberKey: String) {
        if (openMemberKey == memberKey) {
            openMemberKey = null
            isViewingLatest = true
        }
    }

    fun isViewing(memberKey: String): Boolean = appVisible && openMemberKey == memberKey && isViewingLatest
}
