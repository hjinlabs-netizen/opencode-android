package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.OpenCodeHttpException
import com.anomalyco.opencode.data.remote.UnsupportedResponseException
import com.anomalyco.opencode.data.remote.dto.FileContentDto
import com.anomalyco.opencode.data.remote.dto.FileDiffDto
import com.anomalyco.opencode.data.remote.dto.FileListWrapperDto
import com.anomalyco.opencode.data.remote.dto.FileNodeDto
import com.anomalyco.opencode.data.remote.requireActiveServer
import com.anomalyco.opencode.data.remote.toFriendlyApiException
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.model.ServerConfig
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.FileRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [FileRepository].
 *
 * File/diff route paths are NOT stable across OpenCode server builds: this
 * one serves `/fs/list` + `/fs/diff`, the stock server exposes `/file` and
 * `/vcs/diff`, and unknown routes answer 200 with the SPA `index.html`
 * (which previously crashed ContentNegotiation with
 * `NoTransformationFoundException`). Every operation therefore walks a small
 * candidate chain, treating 404 / non-JSON / unparseable bodies as "wrong
 * endpoint" and only surfacing a friendly error when all candidates miss.
 *
 * An empty directory path omits the `path` query parameter entirely (many
 * servers reject `path=` but default to the project root when absent).
 */
@Singleton
class FileRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val connectionRepository: ConnectionRepository,
    private val json: Json,
) : FileRepository {

    override suspend fun listDirectory(path: String): Result<List<FileNode>> = guarded {
        val server = connectionRepository.requireActiveServer()
        val element = firstUsableJson(server, LIST_ENDPOINTS, path)
        decodeNodes(element, path)
    }

    override suspend fun readFile(path: String): Result<FileContent> = guarded {
        val server = connectionRepository.requireActiveServer()
        val element = firstUsableJson(server, READ_ENDPOINTS, path)
        if (element !is JsonObject) {
            throw IllegalStateException("Dosya içeriği okunamadı: $path")
        }
        json.decodeFromJsonElement(FileContentDto.serializer(), element).toDomain(requestedPath = path)
    }

    override suspend fun diffFile(path: String): Result<FileDiff> = guarded {
        val server = connectionRepository.requireActiveServer()
        val rows = decodeDiffs(firstUsableJson(server, DIFF_ENDPOINTS, path))
        rows.firstOrNull { it.path == path }
            ?: rows.firstOrNull()
            ?: throw IllegalStateException("Bu dosya için değişiklik bulunamadı: $path")
    }

    /**
     * Working-tree overview: when no diff endpoint exists at all this
     * degrades to an empty list (the "no changes" screen) instead of
     * erroring — the diff view is informational, not blocking.
     */
    override suspend fun workingTreeDiff(): Result<List<FileDiff>> =
        runCatching {
            val server = connectionRepository.requireActiveServer()
            decodeDiffs(firstUsableJson(server, DIFF_ENDPOINTS, path = null))
                .filter { it.hunks.isNotEmpty() || it.status != DiffStatus.UNCHANGED }
        }.recoverCatching { failure ->
            if (isEndpointMiss(failure)) emptyList() else throw failure.toFriendlyApiException()
        }

    // ---- endpoint candidate chain ------------------------------------------

    private suspend fun firstUsableJson(
        server: ServerConfig,
        endpoints: List<String>,
        path: String?,
    ): JsonElement {
        var lastError: Throwable =
            IllegalStateException("Dosya uç noktaları yanıt vermedi: ${endpoints.joinToString()}")
        val query = if (path.isNullOrBlank()) emptyMap() else mapOf("path" to path)
        for (endpoint in endpoints) {
            val attempt = runCatching { api.getJson(server.baseUrl, server.token, endpoint, query) }
            val error = attempt.exceptionOrNull()
            if (error == null) return attempt.getOrThrow()
            if (!isEndpointMiss(error)) throw error // auth/network: fail fast, no probing
            lastError = error
        }
        throw lastError
    }

    /** 404, SPA-fallback HTML or unparseable JSON mean "try the next path". */
    private fun isEndpointMiss(error: Throwable?): Boolean =
        error is UnsupportedResponseException ||
            (error is OpenCodeHttpException && error.code == 404) ||
            error is kotlinx.serialization.SerializationException

    // ---- tolerant shape decoding --------------------------------------------

    private fun decodeNodes(element: JsonElement, parentPath: String): List<FileNode> =
        when (element) {
            is JsonArray -> element.mapNotNull { item ->
                runCatching { json.decodeFromJsonElement(FileNodeDto.serializer(), item) }
                    .getOrNull()
            }.map { it.toDomain(parentPath) }

            is JsonObject -> {
                val wrapper = runCatching {
                    json.decodeFromJsonElement(FileListWrapperDto.serializer(), element)
                }.getOrNull() ?: return emptyList()
                val rows = wrapper.entries.ifEmpty { wrapper.children }
                val base = wrapper.path.ifBlank { parentPath }
                rows.map { it.toDomain(base) }
            }

            else -> emptyList()
        }

    private fun decodeDiffs(element: JsonElement): List<FileDiff> = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(FileDiffDto.serializer(), item) }.getOrNull()
        }.map { it.toDomain() }

        is JsonObject -> runCatching {
            json.decodeFromJsonElement(
                com.anomalyco.opencode.data.remote.dto.DiffTextDto.serializer(),
                element,
            )
        }.getOrNull()?.toDomain() ?: emptyList()

        else -> emptyList()
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        runCatching { block() }
            .recoverCatching { throw it.toFriendlyApiException() }

    private companion object {
        val LIST_ENDPOINTS = listOf("/fs/list", "/file")
        val READ_ENDPOINTS = listOf("/fs/read", "/file")
        val DIFF_ENDPOINTS = listOf("/fs/diff", "/vcs/diff")
    }
}
