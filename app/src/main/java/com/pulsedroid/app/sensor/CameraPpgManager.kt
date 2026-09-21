package com.pulsedroid.app.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Camera2 manager for Photoplethysmography (PPG).
 * Activates rear camera flash in torch mode and acquires video frames via ImageReader.
 * (Ported and modernized from ubicomplab/Seismo PulseSensing.java)
 */
class CameraPpgManager(
    private val context: Context,
    private val onPpgSample: (timestampNs: Long, red: Double, green: Double, blue: Double) -> Unit,
    private val onError: (message: String) -> Unit
) {
    private val tag = "CameraPpgManager"

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    var isRunning = false
        private set

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val planes = image.planes
            if (planes.isNotEmpty()) {
                val yBuffer: ByteBuffer = planes[0].buffer
                val uBuffer: ByteBuffer = if (planes.size > 1) planes[1].buffer else yBuffer
                val vBuffer: ByteBuffer = if (planes.size > 2) planes[2].buffer else yBuffer

                // Sample luminance across planes
                var sumY = 0.0
                var sumU = 0.0
                var sumV = 0.0
                val capacity = yBuffer.remaining()
                val step = (capacity / 1000).coerceAtLeast(1)

                var count = 0
                var i = 0
                while (i < capacity) {
                    val y = (yBuffer.get(i).toInt() and 0xFF).toDouble()
                    sumY += y
                    count++
                    i += step
                }

                val avgY = if (count > 0) sumY / count else 0.0
                // Approximate RGB luminance for capillary pulse
                val red = -avgY
                val green = -avgY * 0.7
                val blue = -avgY * 0.3

                val now = System.nanoTime()
                onPpgSample(now, red, green, blue)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing image frame", e)
        } finally {
            image.close()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            startPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            onError("Camera error code: $error")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        startBackgroundThread()

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = findBackCameraId(manager)
            if (cameraId == null) {
                onError("No rear camera found with flash")
                return
            }

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                onError("Camera lock timeout")
                return
            }

            // Setup small ImageReader (e.g. 176x144 or 320x240) to minimize CPU load
            val targetSize = Size(176, 144)
            imageReader = ImageReader.newInstance(
                targetSize.width,
                targetSize.height,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            manager.openCamera(cameraId, stateCallback, backgroundHandler)
            isRunning = true
        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            onError("Camera access exception: ${e.message}")
        } catch (e: SecurityException) {
            cameraOpenCloseLock.release()
            onError("Camera permission denied")
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            onError("Unexpected camera error: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning) return
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
            isRunning = false
        }
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        try {
            val surface = reader.surface
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                // Turn on camera flash as torch
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to start camera repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        onError("Failed to configure camera capture session")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            onError("Failed to create capture session: ${e.message}")
        }
    }

    private fun findBackCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraPpgBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "Interrupted while stopping background thread", e)
        }
    }
}
