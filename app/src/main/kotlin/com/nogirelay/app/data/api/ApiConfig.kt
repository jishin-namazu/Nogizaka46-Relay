package com.nogirelay.app.data.api

import com.nogirelay.app.BuildConfig

object ApiConfig {

    val BASE_URL: String
        get() = BuildConfig.DEFAULT_RELAY_URL

    val ACCESS_TOKEN: String
        get() = BuildConfig.RELAY_ACCESS_TOKEN
}
