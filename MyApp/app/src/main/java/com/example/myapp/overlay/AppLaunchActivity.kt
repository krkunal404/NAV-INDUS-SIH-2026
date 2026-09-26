package com.example.myapp.overlay

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * Briefly highlights the app Nimo is about to open (icon + name, with a
 * short pop-in animation) before actually launching it, so the user gets
 * clear visual confirmation instead of the app appearing instantly with
 * no feedback.
 */
class AppLaunchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_LABEL = "extra_app_label"
        private const val HIGHLIGHT_DURATION_MS = 900L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (targetPackage.isNullOrBlank()) {
            finish()
            return
        }
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL)?.takeIf { it.isNotBlank() }
            ?: targetPackage

        val iconDrawable: Drawable? = try {
            packageManager.getApplicationIcon(targetPackage)
        } catch (e: Exception) {
            null
        }

        setContent {
            AppLaunchHighlight(appLabel = appLabel, iconDrawable = iconDrawable)
        }

        mainHandler.postDelayed({
            launchTargetApp(targetPackage)
        }, HIGHLIGHT_DURATION_MS)
    }

    private fun launchTargetApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}

@Composable
private fun AppLaunchHighlight(appLabel: String, iconDrawable: Drawable?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "appIconScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(250),
        label = "appIconAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0B0D14)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1B1E2E))
                .border(3.dp, Color(0xFF00E5FF), RoundedCornerShape(28.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (iconDrawable != null) {
                val bitmap = remember(iconDrawable) {
                    iconDrawable.toBitmap(width = 240, height = 240)
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = appLabel,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = appLabel,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Opening…",
                color = Color(0xFF00E5FF),
                fontSize = 14.sp
            )
        }
    }
}