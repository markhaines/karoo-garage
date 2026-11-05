package com.hainesy.karoogarage

import java.io.IOException

/**
 * A non-2xx HTTP response, carrying the status for callers that branch on it
 * (401 → retry with fresh token, 400 on refresh → re-login required).
 */
class HttpStatusException(
    val statusCode: Int,
    detail: String? = null,
) : IOException(if (detail != null) "HTTP $statusCode: $detail" else "HTTP $statusCode")

/**
 * Thrown when OAuth credentials are dead (revoked, expired after 90 idle days,
 * or wiped by an HA reinstall) and the user has to log in again in the app.
 */
class ReauthRequiredException(message: String) : IOException(message)
