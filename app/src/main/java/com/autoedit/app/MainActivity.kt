package com.autoedit.app

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContent {
            AutoEditTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as Application
    val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app))
    val ui by vm.ui.collectAsState()

    var bootstrapped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1300)
        bootstrapped = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AeBg),
        contentAlignment = Alignment.Center
    ) {
        if (!bootstrapped) {
            SplashScreen()
        } else {
            // AnimatedContent guarantees the previous screen is removed before the
            // next one is shown (short cross-fade), so no stale Composable/canvas
            // content can "bleed through" during fast navigation.
            val target: @Composable () -> Unit = when {
                ui.showStorage -> @Composable { StorageScreen(vm) }
                ui.screen == AppViewModel.Screen.Editor && ui.projectId != null ->
                    @Composable { EditorScreen(vm) }
                else -> @Composable { HomeScreen(vm) }
            }
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) +
                        slideInHorizontally(tween(220)) { it / 16 }) togetherWith
                        fadeOut(animationSpec = tween(140))
                },
                label = "screen"
            ) { content -> content() }
            ToastBar(ui.toast, ui.toastAt, vm::dismissToast)
        }
    }
}

@Composable
fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        AutoEditLogo(size = 120.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = "PREPARING…",
            style = MaterialTheme.typography.labelMedium,
            color = AeTextDim
        )
    }
}

@Composable
fun ToastBar(message: String?, toastAt: Long, onDismiss: () -> Unit) {
    if (message == null) return
    LaunchedEffect(toastAt) {
        delay(2600)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(AeSurface3)
                .border(1.dp, AeLine.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AeText
            )
        }
    }
}
