package com.example.idautble

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read one immutable selection; count actual bytes rather than trusting provider metadata. */
internal suspend fun loadOtaFirmware(context: Context, uri: Uri): OtaFirmware? =
    withContext(Dispatchers.IO) {
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                /* Some providers only grant temporary access. */
            }
            var name = uri.lastPathSegment ?: "Firmware"
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) name = cursor.getString(column) ?: name
                    }
                }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    size += count
                }
            } ?: return@withContext null
            OtaFirmware(
                uri.toString(),
                name,
                size,
                digest.digest().joinToString("") { "%02x".format(it) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
