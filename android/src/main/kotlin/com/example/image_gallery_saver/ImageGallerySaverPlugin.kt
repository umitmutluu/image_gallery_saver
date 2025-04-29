package com.example.image_gallery_saver      // pubspec.yaml ile uyumlu

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.*

class ImageGallerySaverPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private var appContext: Context? = null

    // ---------------- FlutterPlugin lifecycle ----------------
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "image_gallery_saver")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        appContext = null
    }

    // ---------------- Method channel ----------------
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "saveImageToGallery" -> {
                val bytes = call.argument<ByteArray?>("imageBytes")
                val quality = call.argument<Int?>("quality")
                val name = call.argument<String?>("name")
                val bmp = BitmapFactory.decodeByteArray(bytes ?: ByteArray(0), 0, bytes?.size ?: 0)
                result.success(saveImageToGallery(bmp, quality, name))
            }
            "saveFileToGallery" -> {
                val path = call.argument<String?>("file")
                val name = call.argument<String?>("name")
                result.success(saveFileToGallery(path, name))
            }
            else -> result.notImplemented()
        }
    }

    // ---------------- Core ----------------
    private fun saveImageToGallery(bmp: Bitmap?, quality: Int?, name: String?): Map<String, Any?> {
        if (bmp == null || quality == null) return saveResult(false, error = "parameters error")
        val ctx = appContext ?: return saveResult(false, error = "applicationContext null")

        var uri: Uri? = null
        ctx.contentResolver.openOutputStream(generateUri("jpg", name).also { uri = it })?.use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, os)
            os.flush()
        }
        bmp.recycle()

        return if (uri != null) {
            sendBroadcast(ctx, uri)
            saveResult(true, uri.toString())
        } else saveResult(false, error = "saveImageToGallery fail")
    }

    private fun saveFileToGallery(path: String?, name: String?): Map<String, Any?> {
        if (path == null) return saveResult(false, error = "parameters error")
        val ctx = appContext ?: return saveResult(false, error = "applicationContext null")

        val src = File(path)
        if (!src.exists()) return saveResult(false, error = "$path does not exist")

        var uri: Uri? = null
        ctx.contentResolver.openOutputStream(generateUri(src.extension, name).also { uri = it })?.use { out ->
            FileInputStream(src).use { inp -> inp.copyTo(out, 10240) }
            out.flush()
        }

        return if (uri != null) {
            sendBroadcast(ctx, uri)
            saveResult(true, uri.toString())
        } else saveResult(false, error = "saveFileToGallery fail")
    }

    // ---------------- Helpers ----------------
    private fun generateUri(ext: String = "", name: String? = null): Uri? {
        val fileName = name ?: System.currentTimeMillis().toString()
        val mime = ext.takeIf { it.isNotEmpty() }?.let {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase())
        }
        val isVideo = mime?.startsWith("video") == true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName${if (ext.isNotEmpty()) ".$ext" else ""}")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                mime?.let { put(
                    if (isVideo) MediaStore.Video.Media.MIME_TYPE else MediaStore.Images.Media.MIME_TYPE, it) }
            }
            appContext?.contentResolver?.insert(base, values)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
            if (!dir.exists()) dir.mkdirs()
            Uri.fromFile(File(dir, "$fileName${if (ext.isNotEmpty()) ".$ext" else ""}"))
        }
    }

    private fun sendBroadcast(ctx: Context, uri: Uri?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && uri != null) {
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
        }
    }

    private fun saveResult(success: Boolean, path: String? = null, error: String? = null) =
        hashMapOf<String, Any?>("isSuccess" to success, "filePath" to path, "errorMessage" to error)
}