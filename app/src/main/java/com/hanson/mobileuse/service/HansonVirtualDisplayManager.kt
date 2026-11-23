package com.hanson.mobileuse.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class VirtualDisplayStatus(
    val active: Boolean = false,
    val displayId: Int = -1,
    val width: Int = 1080,
    val height: Int = 1920,
    val dpi: Int = 320,
    val lastCaptureTimestampMs: Long = 0L
)

/**
 * Background VirtualDisplay Engine absorbed and security-hardened from `PhoneHarness/PhoneHarness` (`VDService.java`).
 *
 * Uses `DisplayManager.createVirtualDisplay(...)` + `ImageReader` (NOT `MediaProjection`) to create
 * an independent virtual display (`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_PUBLIC`)
 * where background apps (`am start --display <displayId>`) can render and be captured WITHOUT
 * interrupting the user's primary screen.
 *
 * Security Hardening vs Upstream `VDService.java`:
 * - NEVER writes display IDs or screenshots to world-readable `/sdcard/`.
 * - Captures exclusively into in-memory PNG bytes or app-private `context.cacheDir/vdisplay/`.
 * - Exposed ONLY over token-authenticated `ArtemisCommandServer` (`127.0.0.1:18888`, `X-Artemis-Token`).
 */
object HansonVirtualDisplayManager {

    private const val TAG = "HansonVirtualDisplay"
    private val lock = Any()

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val _status = MutableStateFlow(VirtualDisplayStatus())
    val status: StateFlow<VirtualDisplayStatus> = _status.asStateFlow()

    fun start(
        context: Context,
        width: Int = 1080,
        height: Int = 1920,
        dpi: Int = 320
    ): VirtualDisplayStatus {
        synchronized(lock) {
            if (virtualDisplay != null && _status.value.active) {
                return _status.value
            }
            val safeW = width.coerceIn(360, 1440)
            val safeH = height.coerceIn(640, 3200)
            val safeDpi = dpi.coerceIn(160, 560)

            return try {
                val reader = ImageReader.newInstance(safeW, safeH, PixelFormat.RGBA_8888, 2)
                reader.setOnImageAvailableListener({ /* frames polled on demand */ }, null)
                val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val vd = dm.createVirtualDisplay(
                    "hanson-bg-display",
                    safeW,
                    safeH,
                    safeDpi,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                )
                if (vd == null) {
                    reader.close()
                    Log.e(TAG, "DisplayManager.createVirtualDisplay returned null")
                    return VirtualDisplayStatus(active = false)
                }
                imageReader = reader
                virtualDisplay = vd
                val id = vd.display.displayId
                val next = VirtualDisplayStatus(
                    active = true,
                    displayId = id,
                    width = safeW,
                    height = safeH,
                    dpi = safeDpi
                )
                _status.value = next
                Log.i(TAG, "Created background VirtualDisplay id=$id (${safeW}x${safeH}@${safeDpi}dpi)")
                next
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating VirtualDisplay: ${e.message}", e)
                stopLocked()
                VirtualDisplayStatus(active = false)
            }
        }
    }

    /** Captures the latest frame from the VirtualDisplay as PNG bytes (never writes to `/sdcard/`). */
    fun capturePngBytes(context: Context? = null): ByteArray? {
        synchronized(lock) {
            val reader = imageReader ?: return null
            val st = _status.value
            if (!st.active) return null
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
            try {
                val plane = image.planes.firstOrNull() ?: return null
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val rowStride = plane.rowStride
                val rowPadding = (rowStride - pixelStride * st.width).coerceAtLeast(0)
                val paddedWidth = st.width + rowPadding / pixelStride

                val rawBitmap = Bitmap.createBitmap(paddedWidth, st.height, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)
                val finalBitmap = if (rawBitmap.width != st.width) {
                    Bitmap.createBitmap(rawBitmap, 0, 0, st.width, st.height).also { rawBitmap.recycle() }
                } else {
                    rawBitmap
                }
                val baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 88, baos)
                finalBitmap.recycle()
                val bytes = baos.toByteArray()

                if (context != null) {
                    runCatching {
                        val privateDir = File(context.applicationContext.cacheDir, "vdisplay")
                        if (!privateDir.exists()) privateDir.mkdirs()
                        FileOutputStream(File(privateDir, "latest_frame.png")).use { it.write(bytes) }
                    }
                }
                _status.value = st.copy(lastCaptureTimestampMs = System.currentTimeMillis())
                return bytes
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay frame capture failed: ${e.message}")
                return null
            } finally {
                runCatching { image.close() }
            }
        }
    }

    fun captureBase64Png(context: Context? = null): String? {
        val bytes = capturePngBytes(context) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        _status.value = VirtualDisplayStatus(active = false)
    }
}
