package com.nogirelay.app.data

import java.io.IOException

/** A server answered, but not with success. */
class HttpStatusException(val status: Int, message: String) : IOException(message)
