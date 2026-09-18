package com.nogirelay.app.data

/** 进程内的可见性状态，用于在消息所属会话仍显示在屏幕上时避免将其标记为未读。 */
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
