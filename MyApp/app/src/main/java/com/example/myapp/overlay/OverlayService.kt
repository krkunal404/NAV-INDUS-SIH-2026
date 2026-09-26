package com.example.myapp.overlay

import android.R
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapp.speech.TTSManager
import kotlin.math.abs

/**
 * Small draggable floating bubble (Grammarly / chat-head style) that sits
 * on top of other apps.
 *
 * - Tap the bubble -> starts voice capture (bubble pulses while listening).
 * - Long-press the bubble -> opens the text-input overlay instead.
 * - Drag the bubble -> it snaps to the nearest screen edge on release.
 * - Nimo's status/replies appear in a small floating card next to the
 *   bubble, which auto-fades out after a few seconds.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null

    private var bubbleView: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleIcon: ImageView? = null
    private var pulseAnimator: ValueAnimator? = null

    private var closeButtonView: FrameLayout? = null
    private var closeButtonParams: WindowManager.LayoutParams? = null

    private var responseCardView: LinearLayout? = null
    private var responseCardParams: WindowManager.LayoutParams? = null
    private var responseLabel: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideResponseRunnable: Runnable? = null

    companion object {
        const val CHANNEL_ID = "nimo_overlay_channel"
        const val NOTIF_ID = 1001
        var onMicTapped: (() -> Unit)? = null
        var onQuerySubmitted: ((String) -> Unit)? = null

        private var instance: OverlayService? = null

        fun updateStatus(text: String) {
            instance?.showResponseCard(text)
        }

        private const val MIC_BG = "#00D26A"          // Idle - Emerald Green
        private const val MIC_BG_LISTENING = "#00E5FF" // Listening - Bright Cyan
        private const val CARD_BG = "#EA181B26"        // Deep Midnight Slate, ~92% opacity
        private const val TEXT_MAIN = "#F8FAFC"

        private const val BUBBLE_SIZE_PX = 168
        private const val CLOSE_BTN_SIZE_PX = 52
        private const val TAP_MAX_MOVEMENT_PX = 24
        private const val LONG_PRESS_MS = 500L
        private const val RESPONSE_AUTO_HIDE_MS = 6000L
        private const val ACTION_STOP = "ACTION_STOP"
        private const val ACTION_HIDE_FROM_NOTIFICATION = "ACTION_HIDE_FROM_NOTIFICATION"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundServiceInternal()
        showBubble()
    }

    private fun startForegroundServiceInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_HIDE_FROM_NOTIFICATION) {
            dismiss()
            stopSelf()
        }
        return START_STICKY
    }

    private fun layoutType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun baseFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun screenWidthPx(): Int = resources.displayMetrics.widthPixels
    private fun screenHeightPx(): Int = resources.displayMetrics.heightPixels

    // ---------------- Floating bubble ----------------

    private fun showBubble() {
        if (bubbleView != null) return
        if (!OverlayController.hasPermission(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(MIC_BG))
            }
            elevation = 24f
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
        }
        bubble.addView(icon, FrameLayout.LayoutParams(72, 72).apply {
            gravity = Gravity.CENTER
        })
        bubbleIcon = icon

        val params = WindowManager.LayoutParams(
            BUBBLE_SIZE_PX,
            BUBBLE_SIZE_PX,
            layoutType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = screenHeightPx() / 3
        }

        attachTouchHandling(bubble, params)

        runCatching {
            windowManager?.addView(bubble, params)
            bubbleView = bubble
            bubbleParams = params
        }.onFailure {
            stopSelf()
            return
        }

        showCloseButton()
    }

    // ---------------- Always-visible close button ----------------
    // Fixes: users had no reliable way to dismiss the floating bubble once
    // it appeared. A small "x" badge sits on the bubble at all times; tapping
    // it fully stops the overlay service (same as the notification action).

    private fun showCloseButton() {
        val bp = bubbleParams ?: return
        if (closeButtonView != null) {
            repositionCloseButton()
            return
        }

        val closeBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B3000000"))
            }
            elevation = 26f
        }
        val closeIcon = TextView(this).apply {
            text = "\u00D7" // ×
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        closeBtn.addView(
            closeIcon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            CLOSE_BTN_SIZE_PX,
            CLOSE_BTN_SIZE_PX,
            layoutType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bp.x + BUBBLE_SIZE_PX - (CLOSE_BTN_SIZE_PX / 2)
            y = bp.y - (CLOSE_BTN_SIZE_PX / 2)
        }

        closeBtn.setOnClickListener {
            dismiss()
            stopSelf()
        }

        runCatching {
            windowManager?.addView(closeBtn, params)
            closeButtonView = closeBtn
            closeButtonParams = params
        }
    }

    private fun repositionCloseButton() {
        val bp = bubbleParams ?: return
        val cp = closeButtonParams ?: return
        cp.x = bp.x + BUBBLE_SIZE_PX - (CLOSE_BTN_SIZE_PX / 2)
        cp.y = bp.y - (CLOSE_BTN_SIZE_PX / 2)
        closeButtonView?.let { runCatching { windowManager?.updateViewLayout(it, cp) } }
    }

    private fun attachTouchHandling(bubble: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTime = 0L
        var moved = false
        var longPressTriggered = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            TTSManager.stop()
            val intent = Intent(this@OverlayService, QueryInputActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > TAP_MAX_MOVEMENT_PX || abs(dy) > TAP_MAX_MOVEMENT_PX) {
                        if (!moved) {
                            moved = true
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager?.updateViewLayout(view, params) }
                        repositionResponseCard()
                        repositionCloseButton()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered &&
                        System.currentTimeMillis() - downTime < LONG_PRESS_MS
                    ) {
                        onBubbleTapped()
                    } else if (moved) {
                        snapToNearestEdge(params, view)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleTapped() {
        TTSManager.stop()
        setListeningVisual(true)
        showResponseCard("Listening...")
        onMicTapped?.invoke()
    }

    private fun setListeningVisual(isListening: Boolean) {
        bubbleView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (isListening) MIC_BG_LISTENING else MIC_BG))
        }
        pulseAnimator?.cancel()
        if (isListening) {
            pulseAnimator = ValueAnimator.ofFloat(1f, 0.5f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim -> bubbleIcon?.alpha = anim.animatedValue as Float }
                start()
            }
        } else {
            bubbleIcon?.alpha = 1f
        }
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams, view: View) {
        val screenWidth = screenWidthPx()
        val bubbleCenterX = params.x + (view.width / 2)
        val targetX = if (bubbleCenterX < screenWidth / 2) 24 else screenWidth - view.width - 24
        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 200
        animator.addUpdateListener { anim ->
            params.x = anim.animatedValue as Int
            runCatching { windowManager?.updateViewLayout(view, params) }
            repositionResponseCard()
            repositionCloseButton()
        }
        animator.start()
    }

    // ---------------- Floating response card ----------------

    private fun showResponseCard(text: String) {
        mainHandler.post {
            val isListeningText = text.equals("Listening...", ignoreCase = true) ||
                    text.startsWith("Listening:")
            setListeningVisual(isListeningText)

            if (responseCardView == null) {
                createResponseCard()
            }
            responseLabel?.text = text
            responseCardView?.let {
                it.animate().cancel()
                it.alpha = 0f
                it.animate().alpha(1f).setDuration(150).start()
            }
            repositionResponseCard()

            hideResponseRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable { hideResponseCard() }
            hideResponseRunnable = runnable
            mainHandler.postDelayed(runnable, RESPONSE_AUTO_HIDE_MS)
        }
    }

    private fun createResponseCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(CARD_BG))
                cornerRadius = 32f
            }
            elevation = 20f
        }
        val label = TextView(this).apply {
            setTextColor(Color.parseColor(TEXT_MAIN))
            textSize = 15f
            maxLines = 5
        }
        card.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        responseLabel = label

        val maxWidthPx = (screenWidthPx() * 0.72f).toInt()
        val params = WindowManager.LayoutParams(
            maxWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            windowManager?.addView(card, params)
            responseCardView = card
            responseCardParams = params
        }
    }

    private fun repositionResponseCard() {
        val bp = bubbleParams ?: return
        val rp = responseCardParams ?: return
        val screenWidth = screenWidthPx()

        val placeOnRight = bp.x < screenWidth / 2
        rp.x = if (placeOnRight) {
            bp.x + BUBBLE_SIZE_PX + 12
        } else {
            (bp.x - rp.width - 12).coerceAtLeast(12)
        }
        rp.y = (bp.y - 8).coerceAtLeast(24)
        responseCardView?.let { runCatching { windowManager?.updateViewLayout(it, rp) } }
    }

    private fun hideResponseCard() {
        responseCardView?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            responseCardView?.let { runCatching { windowManager?.removeView(it) } }
            responseCardView = null
            responseCardParams = null
        }?.start()
        setListeningVisual(false)
    }

    private fun dismiss() {
        pulseAnimator?.cancel()
        hideResponseRunnable?.let { mainHandler.removeCallbacks(it) }
        responseCardView?.let { runCatching { windowManager?.removeView(it) } }
        bubbleView?.let { runCatching { windowManager?.removeView(it) } }
        closeButtonView?.let { runCatching { windowManager?.removeView(it) } }
        responseCardView = null
        bubbleView = null
        closeButtonView = null
        closeButtonParams = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nimo Listening", NotificationManager.IMPORTANCE_MIN
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }

        // A reliable second way to dismiss the floating bubble (besides the
        // on-bubble "x") in case the overlay is ever hard to reach on screen.
        val hideIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_HIDE_FROM_NOTIFICATION
        }
        val hidePendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val hidePendingIntent = android.app.PendingIntent.getService(
            this, 0, hideIntent, hidePendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nimo Assistant Active")
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, "Hide overlay", hidePendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        dismiss()
    }

    override fun onBind(intent: Intent?): IBinder? = null
};