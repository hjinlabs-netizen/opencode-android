package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.OpenCodeHttpException
import com.anomalyco.opencode.data.remote.UnsupportedResponseException
import com.anomalyco.opencode.data.remote.dto.DiffTextDto
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
import kotlinx.serialization.SerializationException
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
 * File/diff route paths are NOT stable across OpenCode server builds: some
 * serve `/fs/list` + `/fs/diff`, the stock server exposes `/find`, `/file`
 * and `/vcs/diff`, unknown routes answer 200 with the SPA `index.html`, and
 * endpoints that exist but cannot serve the request (e.g. `/file` without a
 * path, or any diff route in a NON-GIT working directory) answer HTTP 400.
 * Every operation therefore walks a candidate chain, treating 400/404,
 * non-JSON bodies and unparseable JSON as "wrong endpoint" and only
 * surfacing a friendly error when all candidates miss.
 *
 * Query policy: a concrete path is always sent as `path=`; a blank/root
 * path is omitted for `/fs/list` (which rejects `path=`) but defaults to
 * `.` for the `/find` family, which requires the parameter.
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
     * Working-tree overview. When no diff endpoint exists — or none can
     * serve the request, notably HTTP 400 from `/vcs/diff` in a directory
     * that is not a git repository — this degrades to an empty list so the
     * "Değişiklik yok" screen renders instead of an error snackbar.
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

    /** @param blankPathDefault query value used when the caller has no path. */
    private data class Endpoint(val path: String, val blankPathDefault: String? = null)

    private suspend fun firstUsableJson(
        server: ServerConfig,
        endpoints: List<Endpoint>,
        path: String?,
    ): JsonElement {
        var lastError: Throwable =
            IllegalStateException("Dosya uç noktaları yanıt vermedi: ${endpoints.joinToString { it.path }}")
        for (endpoint in endpoints) {
            val query = when {
                !path.isNullOrBlank() -> mapOf("path" to path)
                endpoint.blankPathDefault != null -> mapOf("path" to endpoint.blankPathDefault)
                else -> emptyMap()
            }
            val attempt = runCatching { api.getJson(server.baseUrl, server.token, endpoint.path, query) }
            val error = attempt.exceptionOrNull()
            if (error == null) return attempt.getOrThrow()
            if (!isEndpointMiss(error)) throw error // auth/network: fail fast, no probing
            lastError = error
        }
        throw lastError
    }

    /**
     * "Wrong endpoint" signals that justify trying the next candidate:
     * 404 (route absent), 400 (route present but cannot serve this request —
     * missing path param, or non-git workspace for diff endpoints), SPA
     * HTML fallback and unparseable JSON.
     */
    private fun isEndpointMiss(error: Throwable?): Boolean =
        error is UnsupportedResponseException ||
            (error is OpenCodeHttpException && (error.code == 404 || error.code == 400)) ||
            error is SerializationException

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
            json.decodeFromJsonElement(DiffTextDto.serializer(), element)
        }.getOrNull()?.toDomain() ?: emptyList()

        else -> emptyList()
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        runCatching { block() }
            .recoverCatching { throw it.toFriendlyApiException() }

    private companion object {
        val LIST_ENDPOINTS = listOf(
            Endpoint("/fs/list"),
            Endpoint("/find", blankPathDefault = "."),
            Endpoint("/file/find", blankPathDefault = "."),
            Endpoint("/file", blankPathDefault = "."),
        )
        val READ_ENDPOINTS = listOf(
            Endpoint("/fs/read"),
            Endpoint("/file"),
        )
        val DIFF_ENDPOINTS = listOf(
            Endpoint("/fs/diff"),
            Endpoint("/vcs/diff"),
        )
    }
}
