package com.shohan.deadzonemirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.shohan.deadzonemirror.data.OverlayBounds
import com.shohan.deadzonemirror.data.OverlayPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.shohan.deadzonemirror.action.START"
        const val ACTION_STOP = "com.shohan.deadzonemirror.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "deadzone_mirror_channel"
        private const val MIN_SIZE_DP = 100
        private const val FRAME_INTERVAL_MS = 66L // ~15 fps, kept light for a budget device
        private const val MAX_GESTURE_DURATION_MS = 15_000L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayFrameView: OverlayFrameView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastFrameTimeMs = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensityDpi = 0

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val gesturePath = Path()
    private var gestureStartTimeMs = 0L
    private var gestureActive = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system (or the user, via the "Stop casting" control) ended
            // the projection. Tear everything down cleanly instead of
            // leaving a dangling capture pipeline.
            stopCaptureAndOverlay()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        handlerThread = HandlerThread("DeadZoneMirrorCapture").also { it.start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        refreshScreenMetrics()
    }

    private fun refreshScreenMetrics() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensityDpi = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForeground must be called immediately, before any
                // heavier setup, to avoid a ForegroundServiceDidNotStartInTime crash.
                startForegroundCompat()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data != null) {
                    beginCapture(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCaptureAndOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_mirror), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        serviceScope.launch {
            val savedBounds = try {
                OverlayPreferences.load(applicationContext)
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                setupOverlayWindow(savedBounds)
                setupCapturePipeline()
            }
        }
    }

    private fun setupOverlayWindow(savedBounds: OverlayBounds?) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        overlayFrameView = OverlayFrameView(this).apply { isClickable = true }

        val density = resources.displayMetrics.density
        val minSizePx = (MIN_SIZE_DP * density).toInt()

        val width = (savedBounds?.width ?: (screenWidth * 0.55f).toInt())
            .coerceIn(minSizePx, screenWidth)
        val height = (savedBounds?.height ?: (screenHeight * 0.55f).toInt())
            .coerceIn(minSizePx, screenHeight)
        val posX = savedBounds?.x ?: (screenWidth - width) / 2
        val posY = savedBounds?.y ?: (screenHeight - height) / 4

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        layoutParams = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }

        overlayFrameView.onForwardTouch = { event ->
            val scaleX = screenWidth.toFloat() / overlayFrameView.width.toFloat()
            val scaleY = screenHeight.toFloat() / overlayFrameView.height.toFloat()
            handleForwardedTouch(event, scaleX, scaleY)
        }
        overlayFrameView.onMoveRequested = { dx, dy ->
            val currentWidth = layoutParams.width
            val currentHeight = layoutParams.height
            layoutParams.x = (layoutParams.x + dx).coerceIn(-currentWidth / 2, screenWidth - currentWidth / 2)
            layoutParams.y = (layoutParams.y + dy).coerceIn(-currentHeight / 2, screenHeight - currentHeight / 2)
            safeUpdateViewLayout()
        }
        overlayFrameView.onMoveFinished = { persistBounds() }
        overlayFrameView.onResizeRequested = { dw, dh ->
            layoutParams.width = (layoutParams.width + dw).coerceIn(minSizePx, screenWidth)
            layoutParams.height = (layoutParams.height + dh).coerceIn(minSizePx, screenHeight)
            safeUpdateViewLayout()
        }
        overlayFrameView.onResizeFinished = { persistBounds() }
        overlayFrameView.onLockToggle = {
            overlayFrameView.isEditMode = !overlayFrameView.isEditMode
        }

        try {
            windowManager.addView(overlayFrameView, layoutParams)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun safeUpdateViewLayout() {
        if (!::overlayFrameView.isInitialized) return
        try {
            windowManager.updateViewLayout(overlayFrameView, layoutParams)
        } catch (e: Exception) {
            // View may already be detached; safe to ignore.
        }
    }

    private fun persistBounds() {
        if (!::layoutParams.isInitialized) return
        val bounds = OverlayBounds(layoutParams.x, layoutParams.y, layoutParams.width, layoutParams.height)
        serviceScope.launch {
            try {
                OverlayPreferences.save(applicationContext, bounds)
            } catch (e: Exception) {
                // Non-fatal: worst case the overlay reopens at the default spot next time.
            }
        }
    }

    private fun setupCapturePipeline() {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (image == null) return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTimeMs >= FRAME_INTERVAL_MS) {
                    lastFrameTimeMs = now
                    val bitmap = imageToBitmap(image)
                    mainHandler.post {
                        if (::overlayFrameView.isInitialized) {
                            overlayFrameView.mirrorBitmap = bitmap
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip this frame rather than crash the capture pipeline.
            } finally {
                image.close()
            }
        }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DeadZoneMirrorCapture",
            screenWidth, screenHeight, screenDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            backgroundHandler
        )
    }

    /** Converts an RGBA_8888 ImageReader frame to a Bitmap, handling row-stride
     *  padding that the buffer commonly has. A fresh Bitmap is allocated per
     *  frame on purpose: reusing one mutable Bitmap across the background
     *  capture thread and the main UI thread's draw pass would risk a
     *  torn-frame or concurrent-mutation crash. */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride

        val raw = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        raw.copyPixelsFromBuffer(buffer)

        if (rowPadding == 0) return raw
        val cropped = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
        raw.recycle()
        return cropped
    }

    private fun handleForwardedTouch(event: MotionEvent, scaleX: Float, scaleY: Float) {
        val realX = (event.x * scaleX).coerceIn(0f, (screenWidth - 1).toFloat())
        val realY = (event.y * scaleY).coerceIn(0f, (screenHeight - 1).toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePath.reset()
                gesturePath.moveTo(realX, realY)
                gestureStartTimeMs = System.currentTimeMillis()
                gestureActive = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureActive) gesturePath.lineTo(realX, realY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureActive) {
                    val duration = (System.currentTimeMillis() - gestureStartTimeMs)
                        .coerceIn(1L, MAX_GESTURE_DURATION_MS)
                    TouchAccessibilityService.instance?.dispatchPathGesture(Path(gesturePath), duration)
                    gestureActive = false
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen dimensions can change on rotation; rebuild just the capture
        // pipeline (not the overlay window or projection) at the new size.
        if (mediaProjection != null) {
            refreshScreenMetrics()
            releaseCapturePipeline()
            setupCapturePipeline()
        }
    }

    private fun releaseCapturePipeline() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) { }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) { }
        imageReader = null
    }

    private fun stopCaptureAndOverlay() {
        releaseCapturePipeline()

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) { }
        mediaProjection = null

        if (::overlayFrameView.isInitialized) {
            try {
                windowManager.removeView(overlayFrameView)
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        stopCaptureAndOverlay()
        handlerThread?.quitSafely()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
