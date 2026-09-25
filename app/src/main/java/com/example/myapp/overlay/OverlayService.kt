package com.example.myapp.overlay

import android.R
import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Calm, elder-friendly overlay:
 * - Single soft accent color (teal) instead of a busy multi-color bar
 * - Larger text for readability
 * - Gentle pulsing dot as the "listening" indicator
 * - Background stays fully visible and usable outside the card
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pulseAnimator: ValueAnimator? = null

    companion object {
        const val CHANNEL_ID = "nimo_overlay_channel"
        const val NOTIF_ID = 1001
        var onMicTapped: (() -> Unit)? = null
        var onQuerySubmitted: ((String) -> Unit)? = null
        private var statusLabelRef: TextView? = null

        fun updateStatus(text: String) {
            statusLabelRef?.post {
                statusLabelRef?.text = text
            }
        }

        // Single calm accent color used throughout the UI
        private const val ACCENT = "#5EC6B8"       // soft teal
        private const val ACCENT_DIM = "#3A5EC6B8" // translucent teal for pills/glow
        private const val CARD_BG = "#EA20242B"    // warm charcoal, ~92% opacity
        private const val TEXT_MAIN = "#F5F5F5"
        private const val TEXT_HINT = "#9AA0A6"
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceInternal()
        showPanel()
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
        if (intent?.action == "ACTION_STOP") {
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

    private fun showPanel() {
        if (panelView != null) return
        if (!OverlayController.hasPermission(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 44, 48, 76)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(CARD_BG))
                cornerRadii = floatArrayOf(56f, 56f, 56f, 56f, 0f, 0f, 0f, 0f)
            }
            elevation = 28f
        }

        // --- Top row: drag handle + close button ---
        val topRow = FrameLayout(this)

        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#555A61"))
                cornerRadius = 8f
            }
        }
        topRow.addView(handle, FrameLayout.LayoutParams(110, 10).apply {
            gravity = Gravity.CENTER
        })

        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor(TEXT_HINT))
            setPadding(14, 14, 14, 14)
            setOnClickListener { dismiss() }
        }
        topRow.addView(closeBtn, FrameLayout.LayoutParams(76, 76).apply {
            gravity = Gravity.END or Gravity.TOP
        })

        card.addView(
            topRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        )

        // --- Status row: pulsing dot + "Listening" label ---
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pulseDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(ACCENT))
            }
        }
        statusRow.addView(pulseDot, LinearLayout.LayoutParams(28, 28).apply {
            marginEnd = 20
        })

        val statusLabel = TextView(this).apply {
            text = "Listening..."
            setTextColor(Color.parseColor(TEXT_MAIN))
            textSize = 18f
        }
        statusLabelRef = statusLabel
        statusRow.addView(statusLabel)

        card.addView(
            statusRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        )

        // Gentle pulse animation on the dot (no harsh multi-color bar)
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                pulseDot.alpha = anim.animatedValue as Float
            }
            start()
        }

        val openInputQuery = {
            val intent = Intent(this@OverlayService, QueryInputActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // --- Input row: mic icon + text field ---
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 22, 32, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2E36"))
                cornerRadius = 100f
            }
            isClickable = true
            setOnClickListener { openInputQuery() }
        }

        val micBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor(ACCENT))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(ACCENT_DIM))
            }
            setPadding(18, 18, 18, 18)
            setOnClickListener { onMicTapped?.invoke() }
        }
        inputRow.addView(micBtn, LinearLayout.LayoutParams(80, 80).apply {
            marginEnd = 20
        })

        val input = EditText(this).apply {
            hint = "What can I help with?"
            setHintTextColor(Color.parseColor(TEXT_HINT))
            setTextColor(Color.parseColor(TEXT_MAIN))
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            isSingleLine = true
            isFocusable = false
            isClickable = false
        }
        inputRow.addView(
            input,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        card.addView(
            inputRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        @Suppress("DEPRECATION")
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        card.translationY = 300f
        card.alpha = 0f

        runCatching {
            windowManager?.addView(card, params)
            panelView = card
            panelParams = params
            card.animate().translationY(0f).alpha(1f).setDuration(200).start()
        }.onFailure {
            stopSelf()
        }
    }

    private fun dismiss() {
        pulseAnimator?.cancel()
        panelView?.animate()?.translationY(300f)?.alpha(0f)?.setDuration(150)?.withEndAction {
            removeViews()
            stopSelf()
        }?.start()
    }

    private fun removeViews() {
        pulseAnimator?.cancel()
        statusLabelRef = null
        panelView?.let { runCatching { windowManager?.removeView(it) } }
        panelView = null
        panelParams = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nimo Listening", NotificationManager.IMPORTANCE_MIN
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nimo is listening")
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeViews()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
