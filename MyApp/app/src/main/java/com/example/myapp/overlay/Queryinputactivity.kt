package com.example.myapp.overlay

import android.app.Activity
import android.content.Context
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

/**
 * A small, transparent activity that only exists to reliably show the
 * keyboard and capture typed text — since a Service-owned overlay window
 * cannot reliably take keyboard focus. Opens instantly, closes itself
 * once the user submits or taps away.
 */
class QueryInputActivity : Activity() {

    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000")) // dim scrim behind the input
            setOnClickListener { finish() } // tap outside to dismiss
        }

        val inputPill = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EA20242B"))
                cornerRadius = 100f
            }
            setPadding(48, 28, 48, 28)
        }

        input = EditText(this).apply {
            hint = "What can I help with?"
            setHintTextColor(Color.parseColor("#9AA0A6"))
            setTextColor(Color.parseColor("#F5F5F5"))
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            isSingleLine = true
            requestFocus()

            setOnEditorActionListener { _, _, _ ->
                submitAndFinish()
                true
            }
        }
        inputPill.addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(
            inputPill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = 40
                marginEnd = 40
                bottomMargin = 220
            }
        )

        setContentView(root)

        // Force-show the keyboard shortly after layout, for devices that need the nudge
        input.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun submitAndFinish() {
        val text = input.text.toString()
        if (text.isNotBlank()) {
            OverlayService.onQuerySubmitted?.invoke(text)
        }
        finish()
    }

    override fun onBackPressed() {
        finish()
    }
}