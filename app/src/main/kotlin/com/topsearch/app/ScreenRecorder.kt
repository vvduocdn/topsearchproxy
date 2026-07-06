package com.topsearch.app

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ScreenRecorder"

class ScreenRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null

    fun start(projection: MediaProjection): Boolean {
        if (mediaRecorder != null) {
            Log.w(TAG, "start: already recording, ignoring")
            return false
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height, dpi) = displayMetrics(wm)

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.getExternalFilesDir(null), "topsearch_$ts.mp4")
        outputFile = file

        val recorder = newMediaRecorder()
        return try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(4_000_000)
                setOutputFile(file.absolutePath)
                prepare()
            }
            virtualDisplay = projection.createVirtualDisplay(
                "TopSearchRecorder",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null, null,
            )
            recorder.start()
            mediaRecorder = recorder
            Log.d(TAG, "Recording started: ${file.absolutePath} ${width}x${height}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            runCatching { recorder.release() }
            outputFile = null
            false
        }
    }

    fun stop(): String? {
        val path = outputFile?.absolutePath
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        outputFile = null

        if (path == null || !File(path).exists()) {
            Log.w(TAG, "stop: no output file at $path")
            return null
        }
        Log.d(TAG, "Recording stopped: $path")
        saveToGallery(path)
        return path
    }

    private fun saveToGallery(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "topsearch_$ts.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TopSearch")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e(TAG, "saveToGallery: failed to create MediaStore entry")
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            Log.d(TAG, "saveToGallery OK: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "saveToGallery failed: ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(wm: WindowManager): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
        } else {
            val m = android.util.DisplayMetrics()
            wm.defaultDisplay.getMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}
