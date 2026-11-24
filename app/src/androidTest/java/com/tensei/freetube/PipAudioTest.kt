package com.tensei.freetube

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.fail
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class PipAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun awaitOnMain(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            instrumentation.runOnMainSync { matched = condition() }
            if (matched) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    @Test fun homeWindowHeadphonesKeepMediaPlaying() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "pip-audio-test.mp4")
        instrumentation.context.assets.open("clip-source.mp4").use { input ->
            file.outputStream().use(input::copyTo)
        }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        try {
            scenario.onActivity { activity = it }
            awaitOnMain("Media session did not connect") { activity.playerHolder?.controller != null }
            instrumentation.runOnMainSync {
                val state = requireNotNull(activity.playerHolder)
                state.open(VideoItem("pip-local", "PiP test", "", null, null, null, 4, 0, null,
                    localUri = Uri.fromFile(file).toString()))
                state.controller!!.repeatMode = Player.REPEAT_MODE_ONE
            }
            awaitOnMain("Local video did not start") { activity.playerHolder?.isPlaying == true }
            shell("input keyevent KEYCODE_HOME")
            awaitOnMain("Home did not enter PiP") { activity.isInPictureInPictureMode }
            SystemClock.sleep(800)
            val xy = IntArray(2)
            instrumentation.runOnMainSync {
                val view = activity.window.decorView
                view.getLocationOnScreen(xy)
                xy[0] += view.width / 2
                xy[1] += view.height / 2
            }
            shell("input tap ${xy[0]} ${xy[1]}")
            val title = context.getString(R.string.pip_background_audio)
            fun findButton(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription == title) return node
                for (index in 0 until node.childCount) findButton(node.getChild(index))?.let { return it }
                return null
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            var pressed = false
            while (!pressed && SystemClock.elapsedRealtime() < deadline) {
                val button = findButton(instrumentation.uiAutomation.rootInActiveWindow)
                if (button != null) {
                    val bounds = android.graphics.Rect()
                    button.getBoundsInScreen(bounds)
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    pressed = true
                } else SystemClock.sleep(100)
            }
            org.junit.Assert.assertTrue("Headphones missing from Android PiP controls", pressed)
            awaitOnMain("PiP did not close with audio playing") {
                !activity.isInPictureInPictureMode && activity.playerHolder?.backgroundAudioActive == true &&
                    activity.playerHolder?.controller?.isPlaying == true
            }
            var position = -1L
            instrumentation.runOnMainSync { position = activity.playerHolder!!.controller!!.currentPosition }
            SystemClock.sleep(600)
            awaitOnMain("Playback did not advance in the background") {
                val controller = activity.playerHolder?.controller
                controller?.isPlaying == true && controller.currentPosition != position
            }
        } finally {
            instrumentation.runOnMainSync { activity.playerHolder?.controller?.stop() }
            scenario.close()
            file.delete()
        }
    }
}
