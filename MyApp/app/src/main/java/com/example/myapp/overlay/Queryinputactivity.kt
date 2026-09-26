package com.example.myapp.overlay

import android.R
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.example.myapp.speech.TTSManager

/**
 * Clean dialog-style activity for keyboard or voice input overlay.
 */
class QueryInputActivity : ComponentActivity() {

    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TTSManager.stop()
        WakeWordManager.stop()

        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000")) // dim background
            setOnClickListener { finish() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EA20242B"))
                cornerRadius = 60f
            }
            setPadding(32, 20, 24, 20)
            isClickable = true
        }

        // Mic Button inside input bar
        val micBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor("#5EC6B8"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A5EC6B8"))
            }
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                startVoiceInActivity()
            }
        }
        container.addView(micBtn, LinearLayout.LayoutParams(76, 76).apply {
            marginEnd = 16
        })

        // Edit Text field
        input = EditText(this).apply {
            hint = "Type a message or query..."
            setHintTextColor(Color.parseColor("#9AA0A6"))
            setTextColor(Color.parseColor("#F5F5F5"))
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()

            setOnEditorActionListener { _, _, _ ->
                submitAndFinish()
                true
            }
        }
        container.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        // Send Button
        val sendBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_menu_send)
            setColorFilter(Color.parseColor("#5EC6B8"))
            setPadding(16, 16, 16, 16)
            setOnClickListener { submitAndFinish() }
        }
        container.addView(sendBtn, LinearLayout.LayoutParams(80, 80))

        root.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = 32
                marginEnd = 32
                bottomMargin = 200
            }
        )

        setContentView(root)

        input.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun startVoiceInActivity() {
        TTSManager.stop()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.setText("")
        input.hint = "Listening... Speak now"

        ActiveVoiceInput.startListening(
            context = this,
            onPartial = { partial ->
                runOnUiThread {
                    TTSManager.stop()
                    input.setText(partial)
                    input.setSelection(partial.length)
                }
            },
            onResult = { resultText ->
                runOnUiThread {
                    TTSManager.stop()
                    input.setText(resultText)
                    submitAndFinish()
                }
            },
            onError = { err ->
                runOnUiThread {
                    OverlayService.updateStatus("Voice ended ($err)")
                    input.hint = "Type a message or query..."
                }
            }
        )
    }

    private fun submitAndFinish() {
        TTSManager.stop()
        val text = input.text.toString().trim()
        if (text.isNotBlank()) {
            OverlayService.onQuerySubmitted?.invoke(text)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ActiveVoiceInput.destroy()
        OverlayController.show(this)
    }
}
