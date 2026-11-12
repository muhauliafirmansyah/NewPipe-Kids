/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.DownloaderImpl

/**
 * Resolve an already-approved video when a combined stream is not available from the extractor.
 * These clients resolve that exact ID only; they never curate the child library.
 * YouTube's client protocol is unofficial; keep the live playback test when updating it.
 */
object YoutubePlaybackFallback {
    private const val ENDPOINT = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false"
    private val jsonType = "application/json".toMediaType()
    private val http by lazy { DownloaderImpl.getInstance().client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    private data class Client(val name: String, val version: String, val id: String, val sdk: Int, val os: String, val agent: String)
    private val clients = listOf(
        Client("ANDROID_KIDS", "7.36.1", "18", 30, "11", "com.google.android.apps.youtube.kids/7.36.1 (Linux; U; Android 11) gzip"),
        Client("ANDROID", "21.03.36", "3", 36, "16", "com.google.android.youtube/21.03.36 (Linux; U; Android 16; US) gzip")
    )

    fun resolve(videoId: String): String {
        val id = VideoIdentity.youtube(videoId).id
        var failure: Exception? = null
        clients.forEach { client ->
            try {
                return request(id, client)
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw error
                failure = error
            }
        }
        throw IllegalArgumentException("No playable video stream", failure)
    }

    private fun request(id: String, client: Client): String {
        val body = """{"context":{"client":{"clientName":"${client.name}","clientVersion":"${client.version}","androidSdkVersion":${client.sdk},"osName":"Android","osVersion":"${client.os}"}},"videoId":"$id","contentCheckOk":true,"racyCheckOk":true}"""
        val request = Request.Builder().url(ENDPOINT)
            .header("User-Agent", client.agent)
            .header("X-YouTube-Client-Name", client.id)
            .header("X-YouTube-Client-Version", client.version)
            .post(body.toRequestBody(jsonType)).build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not resolve video" }
            parse(id, requireNotNull(response.body).string())
        }
    }

    internal fun parse(videoId: String, json: String): String {
        val id = VideoIdentity.youtube(videoId).id
        val response = JsonParser.`object`().from(json)
        require(response.getObject("playabilityStatus").getString("status") == "OK") { "Video unavailable" }
        require(response.getObject("videoDetails").getString("videoId") == id) { "Video ID mismatch" }
        val formats = response.getObject("streamingData").getArray("formats").filterIsInstance<JsonObject>()
        return formats.asSequence().filter {
            it.getString("mimeType")?.startsWith("video/mp4") == true && it.getString("audioQuality") != null
        }.mapNotNull { it.getString("url") }.firstOrNull { url ->
            runCatching {
                val uri = URI(url)
                val host = uri.host?.lowercase().orEmpty()
                uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
                    (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))
            }.getOrDefault(false)
        } ?: throw IllegalArgumentException("No playable video stream")
    }
}
