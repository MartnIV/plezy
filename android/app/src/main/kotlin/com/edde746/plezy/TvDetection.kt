package com.edde746.plezy

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Native mirror of MainActivity.getAndroidTvDetection(): any TV signal counts,
 * except that FEATURE_AUTOMOTIVE and a standalone VR headset veto the verdict
 * outright. Kept in sync with the Dart-facing detection so native gating
 * matches PlatformDetector.isTV().
 */
object TvDetection {
  fun isTv(context: Context): Boolean {
    val pm = context.packageManager
    val uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK

    // A car is never a TV: rotary-only head units report no touchscreen, and an
    // OEM image can carry a stray leanback flag.
    if (pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false

    // Neither is a VR headset. Horizon OS runs flat apps as 2D panels driven by
    // a ray pointer and reports no touchscreen, so the !FEATURE_TOUCHSCREEN
    // clause below would otherwise make every Quest a television.
    if (pm.hasSystemFeature(OCULUS_FEATURE_STANDALONE_VR)) return false

    @Suppress("DEPRECATION")
    return uiModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
      pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
      pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
      pm.hasSystemFeature("amazon.hardware.fire_tv") ||
      !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
  }

  /**
   * Declared by standalone Horizon OS headsets (Quest and family). Identifying
   * the headset positively is deliberate: the touchless + faketouch pair it
   * shares with set-top boxes is a real TV signal there, so the TV rule cannot
   * simply be relaxed.
   */
  const val OCULUS_FEATURE_STANDALONE_VR = "oculus.hardware.standalone_vr"

  fun isVr(context: Context): Boolean =
    context.packageManager.hasSystemFeature(OCULUS_FEATURE_STANDALONE_VR)
}
