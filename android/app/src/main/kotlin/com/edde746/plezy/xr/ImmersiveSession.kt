package com.edde746.plezy.xr

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.edde746.plezy.mpv.ActiveVideoPlayer

/**
 * Owns the native OpenXR session and the Android Surface it composites.
 *
 * The Surface comes from xrCreateSwapchainAndroidSurfaceKHR, so anything that
 * can write to a Surface can be the picture in the headset: a Canvas while this
 * is being brought up, and mpv once it is wired to the player. The application
 * never touches the swapchain images itself.
 */
object ImmersiveSession {
  private const val TAG = "PlezyXR"

  /**
   * Layer resolution. Independent of the headset's panel: it is the size of the
   * picture the compositor samples, so it should follow the video rather than
   * the display.
   */
  const val DEFAULT_WIDTH = 1920
  const val DEFAULT_HEIGHT = 1080

  @JvmStatic
  external fun nativeStart(activity: Activity, width: Int, height: Int): Surface?

  @JvmStatic
  external fun nativeStop()

  private var loaded = false

  @Synchronized
  fun start(activity: Activity, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT): Surface? {
    if (!loaded) {
      try {
        System.loadLibrary("plezy_xr")
        loaded = true
      } catch (error: UnsatisfiedLinkError) {
        Log.e(TAG, "libplezy_xr.so failed to load", error)
        return null
      }
    }
    return nativeStart(activity, width, height)
  }

  @Synchronized
  fun stop() {
    if (loaded) nativeStop()
  }
}

/**
 * Shows playback on the immersive screen.
 *
 * The player itself is not moved: [ActiveVideoPlayer] hands back the same core
 * the panel was driving and it is pointed at this session's swapchain, so
 * decoding, tracks and position carry across untouched. The panel activity
 * stays alive but backgrounded -- finishing it, as Meta's hybrid-app guidance
 * suggests, would take the FlutterEngine and the player down with it.
 *
 * With no player running it falls back to the bring-up test pattern, which also
 * keeps this launchable on its own:
 *   adb shell am force-stop com.edde746.plezy
 *   adb shell am start -n com.edde746.plezy/.xr.ImmersivePlayerActivity
 */
class ImmersivePlayerActivity : Activity() {
  private var surface: Surface? = null
  private var attachedToPlayer = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val started = ImmersiveSession.start(this)
    if (started == null) {
      Log.e(TAG, "immersive session did not start; leaving")
      finish()
      return
    }
    surface = started

    val core = ActiveVideoPlayer.current()
    if (core != null) {
      core.attachExternalVideoSurface(started, ImmersiveSession.DEFAULT_WIDTH, ImmersiveSession.DEFAULT_HEIGHT)
      attachedToPlayer = true
      Log.i(TAG, "video output redirected to the immersive swapchain")
    } else {
      Log.i(TAG, "no active player; showing the bring-up pattern")
      paintTestPattern(started)
    }
  }

  /**
   * Deliberately asymmetric: bright corner blocks and an off-centre bar make it
   * obvious if the layer is mirrored, rotated, letterboxed or cropped, which a
   * plain colour fill or a centred logo would hide.
   */
  private fun paintTestPattern(target: Surface) {
    val canvas: Canvas = try {
      target.lockCanvas(null)
    } catch (error: Throwable) {
      Log.e(TAG, "could not lock the swapchain surface for drawing", error)
      return
    }
    try {
      val w = canvas.width.toFloat()
      val h = canvas.height.toFloat()
      canvas.drawColor(Color.rgb(16, 16, 24))

      val paint = Paint().apply { isAntiAlias = true }
      paint.color = Color.rgb(220, 60, 60)
      canvas.drawRect(0f, 0f, w * 0.12f, h * 0.2f, paint)          // top-left
      paint.color = Color.rgb(60, 200, 90)
      canvas.drawRect(w * 0.88f, 0f, w, h * 0.2f, paint)           // top-right
      paint.color = Color.rgb(70, 120, 240)
      canvas.drawRect(0f, h * 0.8f, w * 0.12f, h, paint)           // bottom-left
      paint.color = Color.rgb(230, 200, 60)
      canvas.drawRect(w * 0.7f, h * 0.45f, w * 0.95f, h * 0.55f, paint)  // off-centre bar

      paint.color = Color.WHITE
      paint.textSize = h * 0.09f
      paint.textAlign = Paint.Align.CENTER
      canvas.drawText("PLEZY VR", w / 2f, h * 0.35f, paint)
      paint.textSize = h * 0.05f
      canvas.drawText("${canvas.width} x ${canvas.height}", w / 2f, h * 0.68f, paint)
    } finally {
      target.unlockCanvasAndPost(canvas)
    }
    Log.i(TAG, "test pattern posted to the swapchain surface")
  }

  override fun onDestroy() {
    super.onDestroy()
    // Order matters: the player has to let go of the swapchain surface before
    // the session tears it down, or mpv keeps writing into freed buffers.
    if (attachedToPlayer) {
      ActiveVideoPlayer.current()?.detachExternalVideoSurface()
      attachedToPlayer = false
    }
    surface = null
    ImmersiveSession.stop()
  }

  private companion object {
    const val TAG = "PlezyXR"
  }
}
