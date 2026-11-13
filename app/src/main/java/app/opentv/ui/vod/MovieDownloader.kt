/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.parser.displayTitle

/**
 * Hands a film's provider stream to the system [DownloadManager], which runs the transfer in the
 * background, shows progress in the notification shade, and resumes after network drops.
 *
 * Android 10+ saves to the public Downloads/OpenTV folder (no storage permission needed).
 * Older devices save to the app's own external folder (Android/data/<app>/files/Movies), which
 * also needs no permission.
 */
object MovieDownloader {

    sealed interface Result {
        data class Started(val fileName: String) : Result
        /** HLS playlists, non-HTTP links, etc. — a single file can't be fetched from these. */
        data object Unsupported : Result
        data class Failed(val reason: String) : Result
    }

    fun enqueue(context: Context, movie: Movie, userAgent: String): Result =
        enqueue(context, movie.streamUrl, movie.displayTitle, movie.containerExtension, userAgent)

    /** An episode, saved as "Show - S01E02 - Title.ext". */
    fun enqueue(context: Context, episode: Episode, seriesTitle: String, userAgent: String): Result {
        val code = "S%02dE%02d".format(episode.season, episode.episodeNumber)
        val title = listOf(seriesTitle, code, episode.title)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(" - ")
        return enqueue(context, episode.streamUrl, title, null, userAgent)
    }

    private fun enqueue(
        context: Context,
        streamUrl: String,
        title: String,
        containerExtension: String?,
        userAgent: String,
    ): Result {
        val url = streamUrl.trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return Result.Unsupported
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Unsupported

        val ext = extensionFor(containerExtension, uri)
        if (ext == "m3u8" || ext == "m3u" || ext == "mpd") return Result.Unsupported

        val fileName = "${sanitize(title)}.$ext"
        return try {
            val request = DownloadManager.Request(uri)
                .setTitle(title)
                .setDescription("OpenTV")
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "OpenTV/$fileName")
            } else {
                request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, fileName)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Result.Started(fileName)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun extensionFor(containerExtension: String?, uri: Uri): String {
        containerExtension
            ?.trim()?.trimStart('.')?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
            ?.let { return it }
        val last = uri.lastPathSegment.orEmpty()
        val dot = last.lastIndexOf('.')
        if (dot in 1 until last.length - 1) {
            val e = last.substring(dot + 1).lowercase()
            if (e.length <= 5 && e.all(Char::isLetterOrDigit)) return e
        }
        return "mp4"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().trim('.')
            .take(120)
            .ifEmpty { "video" }
}
