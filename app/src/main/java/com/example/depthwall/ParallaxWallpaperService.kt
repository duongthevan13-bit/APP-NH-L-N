package com.example.depthwall

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

class ParallaxWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine(), SensorEventListener {
        private val DIR = 1f
        private val BG_ZOOM = 1.22f
        private val FG_ZOOM = 1.27f
        private val BG_MOVE_X = 0.035f
        private val BG_MOVE_Y = 0.028f
        private val FG_MOVE_X = 0.115f
        private val FG_MOVE_Y = 0.085f
        private val SMOOTHING = 0.16f
        private val FROZEN_ZOOM = 1.15f
        private val DEPTH_X = 0.09f
        private val DEPTH_Y = 0.06f
        private val MESH_W = 24
        private val MESH_H = 48
        private val meshVerts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
        private val handler = Handler(Looper.getMainLooper())
        private val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 70 }
        private val extrusionPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { alpha = 38 }
        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var tx = 0f; private var ty = 0f
        private var cx = 0f; private var cy = 0f
        private var visible = false
        private var w = 0; private var h = 0
        private var mediaPlayer: MediaPlayer? = null
        private var videoMode = false
        private var videoCompleted = false
        private var lastFrame: Bitmap? = null
        private var videoStamp = 0L

        private fun isFrozen(): Boolean = videoMode && videoCompleted && lastFrame != null

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                if (videoMode && !isFrozen()) return
                drawFrame()
                handler.postDelayed(this, 16L)
            }
        }

        private fun startLoop() {
            handler.removeCallbacks(frame)
            if (visible) handler.post(frame) else drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            loadImages()
            startOrRefreshContent(holder)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startOrRefreshContent(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopVideo()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                loadImages()
                sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                startOrRefreshContent(surfaceHolder)
            } else {
                sm.unregisterListener(this)
                handler.removeCallbacks(frame)
                mediaPlayer?.pauseSafely()
            }
        }

        override fun onDestroy() {
            sm.unregisterListener(this)
            handler.removeCallbacks(frame)
            stopVideo()
            bg?.recycle(); bg = null
            fg?.recycle(); fg = null
            lastFrame?.recycle(); lastFrame = null
            super.onDestroy()
        }

        override fun onSensorChanged(e: SensorEvent) {
            tx = (-e.values[0] / 7.0f * DIR).coerceIn(-1f, 1f)
            ty = ((e.values[1] - 6.0f) / 7.0f * DIR).coerceIn(-1f, 1f)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        private fun startOrRefreshContent(holder: SurfaceHolder) {
            if (w <= 0 || h <= 0 || !holder.surface.isValid) return
            val video = File(filesDir, "bg.mp4")
            if (video.exists() && video.length() > 0L) {
                if (video.lastModified() != videoStamp) {
                    videoStamp = video.lastModified()
                    videoCompleted = false
                    lastFrame?.recycle(); lastFrame = null
                    stopVideo()
                }
                videoMode = true
                handler.removeCallbacks(frame)
                if (isFrozen()) {
                    startLoop()
                    return
                }
                startVideo(holder, video)
            } else {
                videoMode = false
                videoCompleted = false
                lastFrame?.recycle(); lastFrame = null
                stopVideo()
                loadImages()
                startLoop()
            }
        }

        private fun startVideo(holder: SurfaceHolder, file: File) {
            if (mediaPlayer != null) {
                if (visible && !videoCompleted) mediaPlayer?.startSafely()
                return
            }
            try {
                val player = MediaPlayer()
                mediaPlayer = player
                player.setDataSource(file.absolutePath)
                player.setSurface(holder.surface)
                player.isLooping = false
                player.setVolume(0f, 0f)
                videoCompleted = false
                player.setOnPreparedListener { mp ->
                    mp.isLooping = false
                    try {
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    } catch (e: Exception) {}
                    if (visible && !videoCompleted) mp.startSafely()
                }
                player.setOnCompletionListener {
                    videoCompleted = true
                    captureLastFrame(file)
                }
                player.setOnErrorListener { _, _, _ ->
                    stopVideo()
                    videoMode = false
                    startLoop()
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                stopVideo()
                videoMode = false
                startLoop()
            }
        }

        private fun captureLastFrame(file: File) {
            Thread {
                var bmp: Bitmap? = null
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val times = longArrayOf(
                        dur * 1000L,
                        (dur - 100L).coerceAtLeast(0L) * 1000L,
                        (dur - 300L).coerceAtLeast(0L) * 1000L
                    )
                    for (t in times) {
                        bmp = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bmp != null) break
                    }
                } catch (e: Exception) {
                } finally {
                    try { r.release() } catch (e: Exception) {}
                }
                val result = bmp
                handler.post {
                    if (result != null && videoCompleted && mediaPlayer != null) {
                        lastFrame?.recycle()
                        lastFrame = result
                        stopVideo()
                        startLoop()
                    }
                }
            }.start()
        }

        private fun stopVideo() {
            val p = mediaPlayer
            mediaPlayer = null
            try { p?.setOnPreparedListener(null); p?.setOnErrorListener(null); p?.setOnCompletionListener(null); p?.stop() } catch (e: Exception) {}
            try { p?.reset(); p?.release() } catch (e: Exception) {}
        }

        private fun MediaPlayer.startSafely() { try { if (!isPlaying) start() } catch (e: Exception) {} }
        private fun MediaPlayer.pauseSafely() { try { if (isPlaying) pause() } catch (e: Exception) {} }

        private fun loadImages() {
            if (w <= 0 || h <= 0) return
            bg?.recycle(); bg = decode(File(filesDir, "bg.img"))
            fg?.recycle(); fg = decode(File(filesDir, "fg.img"))
        }

        private fun decode(f: File): Bitmap? {
            if (!f.exists() || f.length() == 0L) return null
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                var sample = 1
                val targetW = (w * 1.35f).toInt().coerceAtLeast(1)
                val targetH = (h * 1.35f).toInt().coerceAtLeast(1)
                while (opts.outWidth / (sample * 2) >= targetW && opts.outHeight / (sample * 2) >= targetH) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } catch (e: Exception) { null }
        }

        private fun matrixFor(b: Bitmap, dx: Float, dy: Float, zoom: Float): Matrix {
            val scale = max(w.toFloat() / b.width, h.toFloat() / b.height) * zoom
            return Matrix().apply {
                postScale(scale, scale)
                postTranslate((w - b.width * scale) / 2f + dx, (h - b.height * scale) / 2f + dy)
            }
        }

        private fun drawLayer(c: Canvas, b: Bitmap, dx: Float, dy: Float, zoom: Float) {
            c.drawBitmap(b, matrixFor(b, dx, dy, zoom), paint)
        }

        private fun drawForegroundMax(c: Canvas, b: Bitmap, dx: Float, dy: Float) {
            val matrix = matrixFor(b, dx, dy, FG_ZOOM)
            c.drawBitmap(b, Matrix(matrix).apply { postTranslate(w * 0.012f, h * 0.010f) }, shadowPaint)
            c.drawBitmap(b, Matrix(matrix).apply { postTranslate(-w * 0.0032f, -h * 0.0024f) }, extrusionPaint)
            c.drawBitmap(b, Matrix(matrix).apply { postTranslate(w * 0.0032f, h * 0.0024f) }, extrusionPaint)
            c.drawBitmap(b, matrix, paint)
        }

        private fun drawDepthImage(c: Canvas, b: Bitmap) {
            val s = max(w.toFloat() / b.width, h.toFloat() / b.height) * FROZEN_ZOOM
            val dw = b.width * s
            val dh = b.height * s
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            var k = 0
            for (i in 0..MESH_H) {
                val v = i.toFloat() / MESH_H
                for (j in 0..MESH_W) {
                    val u = j.toFloat() / MESH_W
                    val px = left + u * dw
                    val py = top + v * dh
                    val nx = px / w - 0.5f
                    val ny = py / h - 0.5f
                    val depth = (1f - sqrt(nx * nx + ny * ny) * 1.6f).coerceIn(0f, 1f)
                    val k2 = depth - 0.45f
                    meshVerts[k++] = px + cx * w * DEPTH_X * k2
                    meshVerts[k++] = py + cy * h * DEPTH_Y * k2
                }
            }
            c.drawBitmapMesh(b, MESH_W, MESH_H, meshVerts, 0, null, 0, paint)
        }

        private fun drawFrame() {
            if (w <= 0 || h <= 0) return
            if (videoMode && !isFrozen()) return
            cx += (tx - cx) * SMOOTHING; cy += (ty - cy) * SMOOTHING
            val holder = surfaceHolder
            val c = try { holder.lockCanvas() } catch (e: Exception) { null } ?: return
            try {
                c.drawColor(Color.BLACK)
                val frozen = lastFrame
                if (videoMode && frozen != null) {
                    drawDepthImage(c, frozen)
                } else {
                    bg?.let { drawLayer(c, it, -cx * w * BG_MOVE_X, -cy * h * BG_MOVE_Y, BG_ZOOM) }
                }
                fg?.let { drawForegroundMax(c, it, cx * w * FG_MOVE_X, cy * h * FG_MOVE_Y) }
            } catch (e: Exception) {
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (e: Exception) {}
            }
        }
    }
}
