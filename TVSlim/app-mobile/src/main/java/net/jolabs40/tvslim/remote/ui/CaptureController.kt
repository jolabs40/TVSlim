package net.jolabs40.tvslim.remote.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.screen.ScreenCapture
import net.jolabs40.tvslim.screen.CaptureCause
import net.jolabs40.tvslim.screen.CaptureResult
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.shell.BinaryReader
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/** A screenshot saved on the phone. */
class PhoneCapture(
    val uri: Uri,
    val png: ByteArray,
    val width: Int,
    val height: Int,
    /** Location shown to the user: `Pictures/TV Slim`, or the app's own folder. */
    val location: String,
)

data class CaptureState(val inProgress: Boolean = false, val last: PhoneCapture? = null)

/**
 * Takes a TV screenshot over the app's ADB session.
 *
 * Android 10+: saved to the gallery under `Pictures/TV Slim`, no permission needed. Older versions: saved in the
 * app's folder and shared through a `FileProvider`, since the gallery would need the storage permission.
 */
class CaptureController(
    private val context: Context,
    private val reader: BinaryReader,
    private val scope: CoroutineScope,
    private val info: () -> DeviceInfo,
    private val connected: () -> Boolean,
    private val show: (String) -> Unit,
) {

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun takeCapture() {
        if (!connected()) return show(context.getString(R.string.msg_connect_first))
        if (_state.value.inProgress) return
        _state.update { it.copy(inProgress = true) }
        scope.launch {
            when (val result = ScreenCapture(reader).takeCapture()) {
                is CaptureResult.Succeeded -> record(result)
                is CaptureResult.Failed -> show(failureMessage(result))
            }
            _state.update { it.copy(inProgress = false) }
        }
    }

    private suspend fun record(result: CaptureResult.Succeeded) {
        val name = ScreenCapture.fileName(info(), LocalDateTime.now(), "png")
        runCatching {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) inGallery(name, result.png) else inApp(name, result.png)
            }
        }.onSuccess { (uri, location) ->
            _state.update { it.copy(last = PhoneCapture(uri, result.png, result.width, result.height, location)) }
        }.onFailure {
            show(context.getString(R.string.capture_write_failed, it.message.orEmpty()))
        }
    }

    /** Android 10+: MediaStore without permission, marked pending until the write completes. */
    private fun inGallery(name: String, png: ByteArray): Pair<Uri, String> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore a refusé l'image")
        try {
            resolver.openOutputStream(uri)?.use { it.write(png) } ?: throw IOException("image non ouverte en écriture")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return uri to "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER"
    }

    /** Before Android 10: the app-specific pictures folder, accessible without permission. */
    private fun inApp(name: String, png: ByteArray): Pair<Uri, String> {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, SUBFOLDER)
        folder.mkdirs()
        val file = File(folder, name).apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.captures", file)
        return uri to folder.path
    }

    /** Android share sheet; read permission is granted for this image only. */
    fun shareIntent(capture: PhoneCapture): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, capture.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            context.getString(R.string.capture_share_title),
        )

    fun close() = _state.update { it.copy(last = null) }

    private fun failureMessage(failure: CaptureResult.Failed): String = context.getString(
        when (failure.cause) {
            CaptureCause.CONNECTION -> R.string.capture_failed_connection
            CaptureCause.REJECTED -> R.string.capture_failed_refused
            CaptureCause.UNREADABLE -> R.string.capture_failed_unreadable
        },
        failure.detail,
    )

    private companion object {
        const val SUBFOLDER = "TV Slim"
    }
}
