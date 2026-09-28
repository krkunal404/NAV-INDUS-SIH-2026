package com.example.myapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Animated launch screen. It is the LAUNCHER activity and simply hands over to
 * MainActivity when the animation ends. No app logic lives here.
 */
class SplashActivity : ComponentActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NimoSplashScreen(onFinished = { goToMain() }) }
    }

    private fun goToMain() {
        if (navigated || isFinishing) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

@Composable
private fun NimoSplashScreen(onFinished: () -> Unit) {
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.82f) }
    val barProgress = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    // gentle "breathing" while the logo is on screen
    val breathing = rememberInfiniteTransition(label = "breathing")
    val pulse by breathing.animateFloat(
        initialValue = 1f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { logoAlpha.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
            launch { logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 120f)) }
            launch {
                delay(500)
                barProgress.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
            }
        }
        delay(250)
        screenAlpha.animateTo(0f, tween(300))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.nimo_splash_bg))
            .graphicsLayer { alpha = screenAlpha.value },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.nimo_splash_logo),
                contentDescription = "Nimo - The Elderly Digital Companion",
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .aspectRatio(882f / 1178f)
                    .graphicsLayer {
                        val s = logoScale.value * pulse
                        scaleX = s
                        scaleY = s
                        alpha = logoAlpha.value
                    }
            )
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x1F1B2A78))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(barProgress.value)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFF08A24), Color(0xFF2E7D32))
                            )
                        )
                )
            }
        }
    }
}
