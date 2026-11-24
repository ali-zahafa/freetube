package com.tensei.freetube

import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat










@Stable
class OrientationController(private val activity: Activity) {
    private enum class Forced { Landscape, Portrait }

    private var forced by mutableStateOf<Forced?>(null)
    private var playerOpen = false

    private val listener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            val physicallyLandscape = degrees in 60..120 || degrees in 240..300
            val physicallyPortrait = degrees <= 30 || degrees >= 330
            when {


                forced == Forced.Landscape && physicallyLandscape && autoRotateOn() -> forced = null
                forced == Forced.Portrait && physicallyPortrait -> forced = null
            }
        }
    }

    private fun autoRotateOn() =
        Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    fun enterFullscreen() { forced = Forced.Landscape }

    /** Leave the player's forced orientation and follow the device again. */
    fun exitFullscreen() {
        forced = null
        apply()
    }

    fun setPlayerOpen(open: Boolean) {
        playerOpen = open
        if (!open) forced = null
        apply()
    }

    fun apply() {
        if (TvMode.isTv) {
            // TVs are physically landscape and must never be rotated into portrait.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            return
        }
        activity.requestedOrientation = when {
            // Browsing must follow the device: tablets and foldables should be able
            // to stay in landscape instead of being locked to portrait.
            !playerOpen -> ActivityInfo.SCREEN_ORIENTATION_USER
            forced == Forced.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            forced == Forced.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    @Composable
    fun Bind(playerOpen: Boolean) {
        DisposableEffect(Unit) {
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }
        LaunchedEffect(playerOpen, forced) { setPlayerOpen(playerOpen) }
    }
}

@Composable
fun rememberOrientationController(activity: Activity) = remember(activity) { OrientationController(activity) }


@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    val dark = isDarkTheme()
    DisposableEffect(hidden, dark) {
        val activity = view.context as? ComponentActivity ?: (view.context as? Activity)
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        fun restoreBars() {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
            if (activity is ComponentActivity) {
                val barStyle = if (dark) {
                    androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                runCatching {
                    activity.enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                    controller?.isAppearanceLightStatusBars = !dark
                    controller?.isAppearanceLightNavigationBars = !dark
                }
            }
        }

        if (hidden) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            restoreBars()
        }
        onDispose { restoreBars() }
    }
}






@Stable
class PipController(private val activity: ComponentActivity) {
    var active by mutableStateOf(activity.isInPictureInPictureMode)
        private set
    val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    var videoBounds: Rect? = null


    private val audioAction = "${activity.packageName}.pip.AUDIO"
    private val playAction = "${activity.packageName}.pip.PLAY_PAUSE"
    private val nextAction = "${activity.packageName}.pip.NEXT"
    private var leavingForAudio = false

    @RequiresApi(26)
    private fun action(name: String, icon: Int, title: String): RemoteAction {
        val intent = Intent(name).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(activity, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(activity, icon), title, title, pending)
    }

    @RequiresApi(26)
    private fun actions(): List<RemoteAction> {
        val owner = activity as? MainActivity ?: return emptyList()
        val state = owner.playerHolder ?: return emptyList()
        if (state.current == null || state.audioOnlyActive || state.isMusicMode ||
            state.webPlayerEnabled || owner.shortsPlayerHolder?.isPlaying == true) return emptyList()
        val playing = state.controller?.playWhenReady == true
        return buildList {
            add(action(audioAction, R.drawable.ic_pip_headphones, activity.getString(R.string.pip_background_audio)))
            add(action(playAction, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                activity.getString(if (playing) R.string.pip_pause else R.string.pip_play)))
            if (state.canSkipNext) add(action(nextAction, android.R.drawable.ic_media_next, activity.getString(R.string.pip_next)))
        }
    }

    fun enter(videoAspect: Float): Boolean {
        if (!supported || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return false

        val ratio = videoAspect.coerceIn(0.42f, 2.38f)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(actions())
            .apply {
                videoBounds?.let { b -> setSourceRectHint(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())) }
            }
            .build()
        return activity.enterPictureInPictureMode(params)
    }

    fun updateAutoPip(enabled: Boolean, videoAspect: Float = 16f / 9f) {
        if (!supported) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val builder = PictureInPictureParams.Builder()
                .setAutoEnterEnabled(enabled)
                .setActions(actions())
            if (enabled) {
                val ratio = videoAspect.coerceIn(0.42f, 2.38f)
                builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
                videoBounds?.let { b ->
                    builder.setSourceRectHint(android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
                }
            }
            activity.setPictureInPictureParams(builder.build())
        }
    }

    @Composable
    fun Bind(state: PlayerState, onClosed: () -> Unit) {
        LaunchedEffect(active, state.isPlaying, state.canSkipNext, state.current?.id) {
            if (active && android.os.Build.VERSION.SDK_INT >= 26) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setActions(actions()).build())
            }
        }
        DisposableEffect(activity) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!activity.isInPictureInPictureMode || state.current == null) return
                    when (intent.action) {
                        audioAction -> {
                            if (leavingForAudio) return
                            leavingForAudio = true
                            if (!state.backgroundAudioActive) state.toggleBackgroundAudio()
                            updateAutoPip(false)
                            if (!activity.moveTaskToBack(true)) leavingForAudio = false
                        }
                        playAction -> state.togglePlay()
                        nextAction -> state.skipNext()
                    }
                }
            }
            val filter = IntentFilter(audioAction).apply { addAction(playAction); addAction(nextAction) }
            ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
                active = info.isInPictureInPictureMode

                if (!active) {
                    if (!leavingForAudio && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) onClosed()
                    leavingForAudio = false
                }
            }
            activity.addOnPictureInPictureModeChangedListener(listener)
            onDispose {
                activity.removeOnPictureInPictureModeChangedListener(listener)
                activity.unregisterReceiver(receiver)
            }
        }
    }
}


val LocalPictureInPicture = staticCompositionLocalOf<PipController?> { null }
