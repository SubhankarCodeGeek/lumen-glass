package com.subhankar.lumenglass.sample

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subhankar.lumenglass.compose.LumenGlassState
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.rememberLumenGlassState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Verifies that `LumenSource` captures Compose content into its substrate.**
 */
@RunWith(AndroidJUnit4::class)
class ComposeSourceCaptureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val probeColor = Color(0xFF00FF00)
    private val probeArgb = 0xFF00FF00.toInt()

    @Test
    fun composeContentReachesTheCapture() {
        var stateRef: LumenGlassState? = null

        composeRule.setContent {
            val glass = rememberLumenGlassState()
            stateRef = glass

            LumenSource(glass, Modifier.size(64.dp).testTag("probe")) {
                Box(Modifier.size(64.dp).background(probeColor))
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("probe").captureToImage()

        val state = stateRef
        assertTrue("LumenGlassState not captured", state != null)
        val layer = com.subhankar.lumenglass.android.LumenGlassTestApi.getSourceLayer(state!!)
        assertTrue("LumenSource did not record into sourceLayer", layer != null)

        val bitmap = runBlocking { layer!!.toImageBitmap() }
        val androidBitmap = bitmap.asAndroidBitmap()
        val software = androidBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val centre = software.getPixel(software.width / 2, software.height / 2)

        assertTrue(
            "The capture produced 0x${centre.toUInt().toString(16)}, expected 0xFF00FF00 (green).",
            centre == probeArgb,
        )
    }
}
