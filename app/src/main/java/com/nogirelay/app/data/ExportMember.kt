package com.nogirelay.app.data

data class ExportMember(
    val memberKey: String,
    val name: String,
    val avatarUrl: String?,
    val category: String,
    val displayOrder: Int,
    val directory: Boolean,
)
