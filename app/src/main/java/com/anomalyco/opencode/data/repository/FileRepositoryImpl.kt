package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.data.remote.OpenCodeApi
import com.anomalyco.opencode.data.remote.requireActiveServer
import com.anomalyco.opencode.data.remote.toFriendlyApiException
import com.anomalyco.opencode.domain.model.DiffStatus
import com.anomalyco.opencode.domain.model.FileContent
import com.anomalyco.opencode.domain.model.FileDiff
import com.anomalyco.opencode.domain.model.FileNode
import com.anomalyco.opencode.domain.repository.ConnectionRepository
import com.anomalyco.opencode.domain.repository.FileRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete [FileRepository]. The API returns raw DTO rows; patch text is
 * parsed into structured [FileDiff]s here so the UI never sees wire formats.
 */
@Singleton
class FileRepositoryImpl @Inject constructor(
    private val api: OpenCodeApi,
    private val connectionRepository: ConnectionRepository,
) : FileRepository {

    override suspend fun listDirectory(path: String): Result<List<FileNode>> = guarded {
        val server = connectionRepository.requireActiveServer()
        api.listFiles(server.baseUrl, server.token, path).map { it.toDomain(path) }
    }

    override suspend fun readFile(path: String): Result<FileContent> = guarded {
        val server = connectionRepository.requireActiveServer()
        api.readFile(server.baseUrl, server.token, path).toDomain(requestedPath = path)
    }

    override suspend fun diffFile(path: String): Result<FileDiff> = guarded {
        val server = connectionRepository.requireActiveServer()
        val rows = api.diffFiles(server.baseUrl, server.token, path)
        rows.firstOrNull { it.targetPath == path || it.targetPath.isBlank() }
            ?.toDomain()
            ?: rows.firstOrNull()?.toDomain()
            ?: throw IllegalStateException("Bu dosya için değişiklik bulunamadı: $path")
    }

    override suspend fun workingTreeDiff(): Result<List<FileDiff>> = guarded {
        val server = connectionRepository.requireActiveServer()
        api.diffFiles(server.baseUrl, server.token, null)
            .map { it.toDomain() }
            .filter { it.hunks.isNotEmpty() || it.status != DiffStatus.UNCHANGED }
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        runCatching { block() }
            .recoverCatching { throw it.toFriendlyApiException() }
}
