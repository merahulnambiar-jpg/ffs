package com.example.onedriveuploader

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal Microsoft Graph client for:
 *  1. Ensuring a destination folder path exists under the user's OneDrive root
 *  2. Uploading a file into that folder (simple upload, suitable for files < 4MB;
 *     for larger files an upload-session/chunked approach is used automatically)
 */
class GraphUploadClient(private val accessToken: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val graphBase = "https://graph.microsoft.com/v1.0/me/drive"
    private val simpleUploadLimit = 4 * 1024 * 1024L // 4MB

    /** Creates folder(s) recursively under root if they don't already exist. Returns nothing; OneDrive PUT-by-path auto-creates folders too, but this is explicit for clarity/logging. */
    fun ensureFolderExists(folderPath: String, onLog: (String) -> Unit) {
        val segments = folderPath.trim('/').split("/").filter { it.isNotBlank() }
        var currentPath = ""
        for (segment in segments) {
            val parentPath = currentPath
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"

            val checkUrl = if (parentPath.isEmpty())
                "$graphBase/root:/$currentPath"
            else
                "$graphBase/root:/$currentPath"

            val checkRequest = Request.Builder()
                .url(checkUrl)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(checkRequest).execute().use { resp ->
                if (resp.isSuccessful) {
                    onLog("Folder exists: $currentPath")
                    return@use
                }
                // Not found -> create it under parent
                val createUrl = if (parentPath.isEmpty())
                    "$graphBase/root/children"
                else
                    "$graphBase/root:/$parentPath:/children"

                val body = JSONObject().apply {
                    put("name", segment)
                    put("folder", JSONObject())
                    put("@microsoft.graph.conflictBehavior", "rename")
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val createRequest = Request.Builder()
                    .url(createUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .post(body)
                    .build()

                client.newCall(createRequest).execute().use { createResp ->
                    if (!createResp.isSuccessful) {
                        throw Exception("Failed to create folder '$currentPath': ${createResp.code} ${createResp.body?.string()}")
                    }
                    onLog("Created folder: $currentPath")
                }
            }
        }
    }

    /** Uploads a single local file into the given OneDrive folder path. */
    fun uploadFile(localFile: File, destinationFolderPath: String, onLog: (String) -> Unit) {
        val cleanFolder = destinationFolderPath.trim('/')
        val remotePath = if (cleanFolder.isEmpty()) localFile.name else "$cleanFolder/${localFile.name}"

        if (localFile.length() <= simpleUploadLimit) {
            uploadSimple(localFile, remotePath, onLog)
        } else {
            uploadLargeFileWithSession(localFile, remotePath, onLog)
        }
    }

    private fun uploadSimple(localFile: File, remotePath: String, onLog: (String) -> Unit) {
        val mediaType = guessMediaType(localFile.name)
        val requestBody = localFile.asRequestBody(mediaType.toMediaTypeOrNull())

        val url = "$graphBase/root:/$remotePath:/content"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .put(requestBody)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Upload failed for ${localFile.name}: ${resp.code} ${resp.body?.string()}")
            }
            onLog("Uploaded: $remotePath (${localFile.length()} bytes)")
        }
    }

    /** Chunked upload for files larger than 4MB, per Graph's upload session API. */
    private fun uploadLargeFileWithSession(localFile: File, remotePath: String, onLog: (String) -> Unit) {
        val sessionUrl = "$graphBase/root:/$remotePath:/createUploadSession"
        val sessionBody = JSONObject().apply {
            put("item", JSONObject().apply {
                put("@microsoft.graph.conflictBehavior", "replace")
            })
        }.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val sessionRequest = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer $accessToken")
            .post(sessionBody)
            .build()

        val uploadUrl: String
        client.newCall(sessionRequest).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Failed to create upload session: ${resp.code} ${resp.body?.string()}")
            }
            val json = JSONObject(resp.body!!.string())
            uploadUrl = json.getString("uploadUrl")
        }

        val chunkSize = 5 * 1024 * 1024 // 5MB chunks (must be multiple of 320KB)
        val totalSize = localFile.length()
        var offset = 0L

        localFile.inputStream().use { input ->
            val buffer = ByteArray(chunkSize)
            while (offset < totalSize) {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) break

                val chunkBytes = buffer.copyOf(bytesRead)
                val rangeEnd = offset + bytesRead - 1

                val chunkRequest = Request.Builder()
                    .url(uploadUrl)
                    .header("Content-Length", bytesRead.toString())
                    .header("Content-Range", "bytes $offset-$rangeEnd/$totalSize")
                    .put(chunkBytes.toRequestBody(null, 0, bytesRead))
                    .build()

                client.newCall(chunkRequest).execute().use { chunkResp ->
                    if (!chunkResp.isSuccessful && chunkResp.code != 202) {
                        throw Exception("Chunk upload failed at offset $offset: ${chunkResp.code} ${chunkResp.body?.string()}")
                    }
                }

                offset += bytesRead
                onLog("Uploaded ${localFile.name}: $offset / $totalSize bytes")
            }
        }
        onLog("Upload complete: $remotePath")
    }

    private fun guessMediaType(fileName: String): String = when {
        fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
        fileName.endsWith(".csv", ignoreCase = true) -> "text/csv"
        else -> "application/octet-stream"
    }
}

private fun ByteArray.toRequestBody(contentType: okhttp3.MediaType?, offset: Int, byteCount: Int): RequestBody {
    return RequestBody.create(contentType, this, offset, byteCount)
}

private fun String.toRequestBody(mediaType: okhttp3.MediaType?): RequestBody =
    RequestBody.create(mediaType, this)
