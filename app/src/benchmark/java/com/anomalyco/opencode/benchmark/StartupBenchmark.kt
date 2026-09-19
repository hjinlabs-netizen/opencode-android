package com.anomalyco.opencode.benchmark

import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-3 baseline profile: captures startup-impacting method traces so the
 * profile generator can pre-compile the hot paths (Compose init, Hilt DI,
 * Ktor client construction, theme resolution).
 *
 * The `collect` block launches [MainActivity] via the default intent and
 * measures cold-start timing. The baseline-profile plugin reads the
 * resulting traces and emits a `baseline-prof.txt` that `profileinstaller`
 * applies at install time.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCold() = rule.measureRepeated(
        packageName = "com.hjinlabs.opencodeclient.debug",
        metrics = listOf(StartupTimingMetric()),
        iterations = 3,
        startupMode = androidx.benchmark.macro.StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
