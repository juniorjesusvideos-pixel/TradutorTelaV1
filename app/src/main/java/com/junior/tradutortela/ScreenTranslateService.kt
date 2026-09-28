package com.junior.tradutortela

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class ScreenTranslateService : Service() {
    companion object {
        const val ACTION_START = "com.junior.tradutortela.START"
        const val ACTION_STOP = "com.junior.tradutortela.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_translation"
        private const val NOTIFICATION_ID = 7101
        private const val FRAME_INTERVAL_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)

    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    private var overlayRoot: LinearLayout? = null
    private var overlayText: TextView? = null
    private var modelReady = false
    private var lastFrameAt = 0L
    private var lastSource = ""
    private var lastTranslation = ""

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val languageIdentifier by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.45f)
                .build()
        )
    }

    private val translator: Translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
        )
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        downloadModel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = readProjectionIntent(intent)
            if (resultCode != 0 && data != null) {
                startProjection(resultCode, data)
            } else {
                updateOverlay("Não foi possível iniciar a captura.")
            }
        }

        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Tradução da tela",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundServiceNotification() {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenTranslateService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Tradutor de Tela ativo")
            .setContentText("Traduzindo inglês para português.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun downloadModel() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                updateOverlay("Tradução pronta. Abra um conteúdo em inglês.")
            }
            .addOnFailureListener {
                modelReady = false
                updateOverlay("Não consegui baixar o modelo. Verifique a internet.")
            }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(resultCode, data)
        projection = p

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }
        projectionCallback = callback
        p.registerCallback(callback, handler)

        val (width, height, density) = screenMetrics()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
        }
        display = p.createVirtualDisplay(
            "TradutorTelaCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )

        showOverlay("Preparando tradução…")
    }

    private fun onImageAvailable(imageReader: ImageReader) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < FRAME_INTERVAL_MS || processing.get()) {
            imageReader.acquireLatestImage()?.close()
            return
        }

        val image = imageReader.acquireLatestImage() ?: return
        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now

        val bitmap = try {
            imageToBitmap(image)
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            processing.set(false)
            return
        }

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                bitmap.recycle()
                val source = result.text
                    .replace(lastTranslation, "", ignoreCase = true)
                    .lines()
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                    .distinct()
                    .joinToString("\n")
                    .take(1600)
                    .trim()

                if (source.length < 3 || source == lastSource || !modelReady) {
                    processing.set(false)
                    return@addOnSuccessListener
                }

                languageIdentifier.identifyLanguage(source)
                    .addOnSuccessListener { language ->
                        if (language == "en" || looksEnglish(source)) {
                            translate(source)
                        } else {
                            processing.set(false)
                        }
                    }
                    .addOnFailureListener {
                        if (looksEnglish(source)) translate(source)
                        else processing.set(false)
                    }
            }
            .addOnFailureListener {
                bitmap.recycle()
                processing.set(false)
            }
    }

    private fun translate(source: String) {
        lastSource = source
        translator.translate(source)
            .addOnSuccessListener { translated ->
                val cleaned = translated.trim().take(1100)
                if (cleaned.isNotBlank()) {
                    lastTranslation = cleaned
                    updateOverlay(cleaned)
                }
                processing.set(false)
            }
            .addOnFailureListener {
                processing.set(false)
            }
    }

    private fun looksEnglish(text: String): Boolean {
        val t = " ${text.lowercase()} "
        val words = listOf(
            " the ", " you ", " your ", " and ", " to ", " of ", " is ",
            " are ", " this ", " that ", " with ", " for ", " attack ",
            " level ", " quest ", " item ", " start ", " settings "
        )
        return words.any { t.contains(it) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (result !== padded) padded.recycle()
        return result
    }

    private fun showOverlay(initialText: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (overlayRoot != null) {
            updateOverlay(initialText)
            return
        }

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(0xEA11131A.toInt())
                setStroke(
                    (density).toInt().coerceAtLeast(1),
                    0x805B5FEF.toInt()
                )
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "EN → PT  •  AO VIVO"
            setTextColor(0xFFB8BAFF.toInt())
            textSize = 12f
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val close = Button(this).apply {
            text = "×"
            setOnClickListener { stopSelf() }
        }
        header.addView(close)
        root.addView(header)

        val textView = TextView(this).apply {
            text = initialText
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            maxLines = 8
        }
        root.addView(textView)
        overlayText = textView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (12 * density).toInt()
        }

        try {
            windowManager.addView(root, params)
            overlayRoot = root
        } catch (_: Throwable) {
            overlayRoot = null
            overlayText = null
        }
    }

    private fun updateOverlay(text: String) {
        handler.post {
            if (overlayRoot == null) showOverlay(text)
            else overlayText?.text = text
        }
    }

    private fun screenMetrics(): Triple<Int, Int, Int> {
        val density = resources.configuration.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), density)
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun readProjectionIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun releaseProjection() {
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        display = null
        reader = null

        projectionCallback?.let {
            try { projection?.unregisterCallback(it) } catch (_: Throwable) {}
        }
        projectionCallback = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
    }

    override fun onDestroy() {
        releaseProjection()
        try { recognizer.close() } catch (_: Throwable) {}
        try { languageIdentifier.close() } catch (_: Throwable) {}
        try { translator.close() } catch (_: Throwable) {}

        overlayRoot?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        overlayRoot = null
        overlayText = null
        processing.set(false)
        super.onDestroy()
    }
}
