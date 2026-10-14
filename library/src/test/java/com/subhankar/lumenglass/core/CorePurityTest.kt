/*
 * Copyright 2026 Subhankar Bag.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.subhankar.lumenglass.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the `core/` purity rule (see `ARCHITECTURE.md` §2).
 *
 * `core/` must be **platform-agnostic** — no `android.*` or `androidx.*` imports — so it is
 * reusable by KMP targets and JVM-unit-testable. This is a **guard**, not a convention: a clean
 * build is not proof, so we scan the sources.
 *
 * The test walks from the current working directory up to the module root and reads every `.kt`
 * under `src/main/java/.../core/`.
 */
class CorePurityTest {

    @Test
    fun coreHasNoAndroidImports() {
        val coreDir = findCoreSourceDir()
        assertTrue("core/ source dir not found from ${File(".").absolutePath}", coreDir != null)

        val offenders = mutableListOf<String>()
        coreDir!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        val target = trimmed.removePrefix("import ").trim()
                        if (target.startsWith("android.") ||
                            target.startsWith("androidx.") ||
                            target.startsWith("android ")
                        ) {
                            offenders += "${file.name}:${index + 1}: $trimmed"
                        }
                    }
                }
            }

        assertTrue(
            "core/ must not import android/androidx — offending imports:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    private fun findCoreSourceDir(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/java/com/subhankar/lumenglass/core")
            if (candidate.isDirectory) return candidate
            val alt = File(dir, "library/src/main/java/com/subhankar/lumenglass/core")
            if (alt.isDirectory) return alt
            dir = dir?.parentFile
        }
        return null
    }
}
