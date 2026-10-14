/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import com.subhankar.lumenglass.android.LumenGlassTestApi
import com.subhankar.lumenglass.compose.LumenSource
import com.subhankar.lumenglass.compose.lumenGlass
import com.subhankar.lumenglass.compose.rememberLumenGlassState
import com.subhankar.lumenglass.core.LensModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **End-to-end verification of the LumenGlass public Compose API**:
 * [rememberLumenGlassState], [LumenSource], and [Modifier.lumenGlass].
 */
@RunWith(AndroidJUnit4::class)
class LumenGlassPublicApiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backdropColor = Color(0xFF00FF00) // Pure green

    @Test
    fun publicApiRefractsAndTintsBackdrop() {
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= LumenGlassTestApi.agslMinApi)

        composeRule.setContent {
            val glass = rememberLumenGlassState()

            Box(Modifier.size(128.dp)) {
                // The source background
                LumenSource(glass, Modifier.fillMaxSize().testTag("source")) {
                    Box(Modifier.fillMaxSize().background(backdropColor))
                }

                // The glass overlay placed in the center of the source
                Box(
                    Modifier
                        .offset(32.dp, 32.dp)
                        .size(64.dp)
                        .lumenGlass(glass) {
                            lens = LensModel.Blend(centre = 0.6f, rim = 0.4f)
                            refraction = 1.0f
                            dispersion = 0f
                            tint = Color.Red.copy(alpha = 0.5f)
                            frost = 0.dp
                        }
                        .testTag("glass")
                )
            }
        }
        composeRule.waitForIdle()

        val captured = composeRule.onNodeWithTag("glass").captureToImage()
        val androidBitmap = captured.asAndroidBitmap()
        val software = androidBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val centre = software.getPixel(software.width / 2, software.height / 2)

        val alpha = (centre ushr 24) and 0xFF
        val red = (centre ushr 16) and 0xFF
        val green = (centre ushr 8) and 0xFF

        assertTrue("Expected non-zero alpha, got $alpha", alpha > 0)
        assertTrue("Expected red tint from glass, got red=$red", red > 0)
        assertTrue("Expected green from backdrop refracted through glass, got green=$green", green > 0)
    }
}
