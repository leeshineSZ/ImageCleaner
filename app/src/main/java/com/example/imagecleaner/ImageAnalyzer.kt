package com.example.imagecleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ImageAnalyzer(private val context: Context) {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(0.1f)
        .build()

    private val detector = FaceDetection.getClient(options)

    suspend fun containsFace(uri: Uri): Boolean {
        val bitmap = decodeBitmap(uri) ?: return false
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = awaitTask(detector.process(image))
        bitmap.recycle()
        return faces.isNotEmpty()
    }

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T {
        return suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { result -> cont.resume(result) }
            task.addOnFailureListener { e -> cont.cancel(e) }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun queryAllImages(): List<ImageData> {
        val images = mutableListOf<ImageData>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val date = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                images.add(ImageData(uri, date, name))
            }
        }

        return images
    }
}
