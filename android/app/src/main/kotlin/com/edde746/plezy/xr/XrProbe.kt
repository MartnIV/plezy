package com.edde746.plezy.xr

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Reports what the headset's OpenXR runtime offers, to logcat.
 *
 * The VR compositor cannot be screen-captured on Horizon OS, so the immersive
 * player is built in steps that each report through logcat instead. This is the
 * first: it proves the loader links, that instance creation works from this
 * process, and which of the extensions the player depends on are present.
 *
 * Launch it with:
 *   adb shell am start -n com.edde746.plezy/.xr.XrProbeActivity
 */
object XrProbe {
  private const val TAG = "PlezyXR"

  @JvmStatic
  external fun nativeProbe(activity: Activity)

  fun run(activity: Activity) {
    try {
      System.loadLibrary("plezy_xr")
    } catch (error: UnsatisfiedLinkError) {
      Log.e(TAG, "libplezy_xr.so failed to load", error)
      return
    }
    nativeProbe(activity)
  }
}

/** Runs [XrProbe] and exits; it renders nothing of its own. */
class XrProbeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    XrProbe.run(this)
    finish()
  }
}
