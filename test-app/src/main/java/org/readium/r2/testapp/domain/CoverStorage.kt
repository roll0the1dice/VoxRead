package org.readium.r2.testapp.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.fetchWithDecoder
import org.readium.r2.testapp.utils.tryOrLog

class CoverStorage(
    private val appStorageDir: File,
    private val httpClient: HttpClient,
) {

    suspend fun storeCover(publication: Publication, overrideUrl: AbsoluteUrl?): Try<File, Exception> {
        val coverBitmap: Bitmap? = overrideUrl?.fetchBitmap()
            ?: publication.cover()
        return try {
            Try.success(storeCover(coverBitmap))
        } catch (e: Exception) {
            Try.failure(e)
        }
    }

    private suspend fun AbsoluteUrl.fetchBitmap(): Bitmap? =
        tryOrLog {
            when {
                isFile -> toFile()?.toBitmap()
                isHttp -> httpClient.fetchBitmap(HttpRequest(this)).getOrNull()
                else -> null
            }
        }

    private suspend fun File.toBitmap(): Bitmap? =
        withContext(Dispatchers.IO) {
            tryOrLog {
                BitmapFactory.decodeFile(path)
            }
        }

    private suspend fun HttpClient.fetchBitmap(request: HttpRequest): Try<Bitmap, HttpError> =
        fetchWithDecoder(request) { response ->
            BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
        }

    private suspend fun storeCover(cover: Bitmap?): File =
        withContext(Dispatchers.IO) {
            val coverImageFile = File(coverDir(), "${Uuid.random()}-aspect-v2.png")
            val resized = cover?.let {
                val scale = minOf(1.0, 600.0 / it.width, 900.0 / it.height)
                Bitmap.createScaledBitmap(it, (it.width * scale).roundToInt().coerceAtLeast(1),
                    (it.height * scale).roundToInt().coerceAtLeast(1), true)
            }
            FileOutputStream(coverImageFile).use { output ->
                resized?.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            coverImageFile
        }

    private fun coverDir(): File =
        File(appStorageDir, "covers/")
            .apply { if (!exists()) mkdirs() }
}
