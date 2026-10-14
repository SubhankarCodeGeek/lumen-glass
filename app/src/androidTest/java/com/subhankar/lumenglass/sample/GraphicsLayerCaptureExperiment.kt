/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.sample

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.subhankar.lumenglass.android.GlassUniforms
import com.subhankar.lumenglass.android.LumenGlassTestApi
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

import androidx.compose.ui.test.junit4.createAndroidComposeRule

/**
 * **Does `GraphicsLayer.record { drawContent() }` actually capture Compose content?**
 */
@RunWith(AndroidJUnit4::class)
class GraphicsLayerCaptureExperiment {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val probeColor = Color(0xFF00FF00)
    private val probeArgb = 0xFF00FF00.toInt()

    @Test
    fun graphicsLayerRecordCapturesComposeContent() {
        var capturedLayer: GraphicsLayer? = null

        composeRule.setContent {
            val layer = rememberGraphicsLayer()
            capturedLayer = layer

            Box(
                Modifier
                    .drawWithContent {
                        layer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(layer)
                    }
                    .testTag("probe"),
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(probeColor),
                )
            }
        }
        composeRule.waitForIdle()
        // Drives a draw pass so drawWithContent runs and records into the layer
        composeRule.onNodeWithTag("probe").captureToImage()

        val layer = capturedLayer
        assertTrue("rememberGraphicsLayer never produced a GraphicsLayer", layer != null)

        // `toImageBitmap()` is suspend; in a test we can simply block.
        val bitmap: ImageBitmap = runBlocking { layer!!.toImageBitmap() }
        val androidBitmap = bitmap.asAndroidBitmap()
        val software = androidBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val centre = software.getPixel(software.width / 2, software.height / 2)

        composeRule.onNodeWithTag("probe").assertExists()

        assertTrue(
            "GraphicsLayer content read back as 0x${centre.toUInt().toString(16)}, expected " +
                "0xFF00FF00 (green). If this is black, `layer.record { drawContent() }` has the " +
                "same defect as the CanvasDrawScope approach and Option 1 does not work as-is.",
            centre == probeArgb,
        )
    }

    @Test
    fun graphicsLayerAppliesGlassRenderEffect() {
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= LumenGlassTestApi.agslMinApi)

        var capturedLayer: GraphicsLayer? = null

        composeRule.setContent {
            val layer = rememberGraphicsLayer()
            capturedLayer = layer

            val density = androidx.compose.ui.platform.LocalDensity.current
            val sizePx = with(density) { 64.dp.toPx() }
            val uniforms = GlassUniforms(
                widthPx = sizePx,
                heightPx = sizePx,
                cornerRadiusPx = 0f,
                centreWeight = 0.6f,
                rimWeight = 0.4f,
                refraction = 1.0f,
                dispersion = 0f,
                tintArgb = 0x80FF0000.toInt(), // translucent red tint
                saturation = 1f,
                contrast = 1f,
                edgeLight = 0f,
                tier = LumenGlassTestApi.tierBalanced,
            )
            val shader = LumenGlassTestApi.buildShader(uniforms)
            val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            layer.renderEffect = effect.asComposeRenderEffect()

            Box(
                Modifier
                    .drawWithContent {
                        layer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(layer)
                    }
                    .testTag("probe-shader"),
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(probeColor),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("probe-shader").captureToImage()

        val layer = capturedLayer
        assertTrue("rememberGraphicsLayer never produced a GraphicsLayer", layer != null)

        val bitmap: ImageBitmap = runBlocking { layer!!.toImageBitmap() }
        val androidBitmap = bitmap.asAndroidBitmap()
        val software = androidBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val centre = software.getPixel(software.width / 2, software.height / 2)

        val alpha = (centre ushr 24) and 0xFF
        val red = (centre ushr 16) and 0xFF
        val green = (centre ushr 8) and 0xFF

        assertTrue("Expected non-zero alpha, got $alpha", alpha > 0)
        assertTrue("Expected red tint blended in, got red=$red", red > 0)
        assertTrue("Expected green channel from backdrop, got green=$green", green > 0)
    }

    @Test
    fun glassLayerSamplesAndRefractsSourceLayer() {
        assumeTrue("AGSL requires API 33+", Build.VERSION.SDK_INT >= LumenGlassTestApi.agslMinApi)

        var capturedGlassLayer: GraphicsLayer? = null

        composeRule.setContent {
            val sourceLayer = rememberGraphicsLayer()
            val glassLayer = rememberGraphicsLayer()
            capturedGlassLayer = glassLayer

            val density = androidx.compose.ui.platform.LocalDensity.current
            val sizePx = with(density) { 64.dp.toPx() }
            val offsetPx = with(density) { 32.dp.toPx() }

            val uniforms = GlassUniforms(
                widthPx = sizePx,
                heightPx = sizePx,
                cornerRadiusPx = 0f,
                centreWeight = 0.6f,
                rimWeight = 0.4f,
                refraction = 1.0f,
                dispersion = 0f,
                tintArgb = 0x80FF0000.toInt(), // translucent red tint
                saturation = 1f,
                contrast = 1f,
                edgeLight = 0f,
                tier = LumenGlassTestApi.tierBalanced,
            )
            val shader = LumenGlassTestApi.buildShader(uniforms)
            val effect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
            glassLayer.renderEffect = effect.asComposeRenderEffect()

            Box(Modifier.size(128.dp)) {
                // Background source
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            sourceLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(sourceLayer)
                        }
                        .testTag("source-probe"),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(probeColor)
                    )
                }

                // Glass overlay
                Box(
                    Modifier
                        .offset(32.dp, 32.dp)
                        .size(64.dp)
                        .drawWithContent {
                            glassLayer.record {
                                translate(-offsetPx, -offsetPx) {
                                    drawLayer(sourceLayer)
                                }
                            }
                            drawLayer(glassLayer)
                        }
                        .testTag("glass-probe")
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("glass-probe").captureToImage()

        val layer = capturedGlassLayer
        assertTrue("glassLayer was not captured", layer != null)

        val bitmap: ImageBitmap = runBlocking { layer!!.toImageBitmap() }
        val androidBitmap = bitmap.asAndroidBitmap()
        val software = androidBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val centre = software.getPixel(software.width / 2, software.height / 2)

        val alpha = (centre ushr 24) and 0xFF
        val red = (centre ushr 16) and 0xFF
        val green = (centre ushr 8) and 0xFF

        assertTrue("Expected non-zero alpha, got $alpha", alpha > 0)
        assertTrue("Expected red tint from glass shader, got red=$red", red > 0)
        assertTrue("Expected green from background sourceLayer, got green=$green", green > 0)
    }
}
