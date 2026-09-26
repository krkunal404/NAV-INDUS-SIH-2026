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
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.myapp.speech.TTSManager

/**
 * Overlay Service providing a clean, accessible interface with
 * separate Voice (Mic) and Text Input controls.
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

        private const val ACCENT = "#5EC6B8"       // soft teal
        private const val ACCENT_DIM = "#3A5EC6B8" // translucent teal for pills/glow
        private const val CARD_BG = "#EA20242B"    // warm charcoal, ~92% opacity
        private const val BUTTON_BG = "#2A2E36"  // dark pill background
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
            setPadding(48, 40, 48, 64)
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
            ).apply { bottomMargin = 24 }
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
            text = "Say \"Hello Nimo\" or tap below..."
            setTextColor(Color.parseColor(TEXT_MAIN))
            textSize = 17f
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
            TTSManager.stop()
            val intent = Intent(this@OverlayService, QueryInputActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            dismiss()
        }

        // --- SEPARATE CONTROL 1: Dedicated Mic Button ---
        val micCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUTTON_BG))
                cornerRadius = 48f
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                TTSManager.stop()
                onMicTapped?.invoke()
            }
        }

        val micIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor(ACCENT))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(ACCENT_DIM))
            }
            setPadding(16, 16, 16, 16)
        }
        micCard.addView(micIcon, LinearLayout.LayoutParams(76, 76).apply {
            marginEnd = 24
        })

        val micText = TextView(this).apply {
            text = "Tap to Speak"
            setTextColor(Color.parseColor(TEXT_MAIN))
            textSize = 17f
        }
        micCard.addView(micText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        card.addView(
            micCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        )

        // --- SEPARATE CONTROL 2: Dedicated Text Input Button / Field ---
        val textCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(BUTTON_BG))
                cornerRadius = 48f
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openInputQuery() }
        }

        val keyboardIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_search)
            setColorFilter(Color.parseColor(TEXT_HINT))
            setPadding(12, 12, 12, 12)
        }
        textCard.addView(keyboardIcon, LinearLayout.LayoutParams(64, 64).apply {
            marginEnd = 24
        })

        val inputHint = TextView(this).apply {
            text = "Type a message or query..."
            setTextColor(Color.parseColor(TEXT_HINT))
            textSize = 17f
        }
        textCard.addView(
            inputHint,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        card.addView(
            textCard,
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
            .setContentTitle("Nimo Assistant Active")
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
