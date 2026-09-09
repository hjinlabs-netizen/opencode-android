package com.anomalyco.opencode.domain.model

import kotlinx.serialization.Serializable

/**
 * Immutable server connection configuration entered by the user.
 *
 * @param baseUrl   Root URL of the OpenCode server, e.g. `http://192.168.1.10:4096`
 *                  or `https://opencode.example.com`. Trailing slashes are tolerated
 *                  and normalized by the data layer.
 * @param token     Optional bearer token / password the server was started with
 *                  (`opencode serve --password ...`). Empty when the server is open.
 */
data class ServerConfig(
    val baseUrl: String,
    val token: String = "",
) {
    val normalizedUrl: String
        get() = baseUrl.trim().removeSuffix("/")
}
