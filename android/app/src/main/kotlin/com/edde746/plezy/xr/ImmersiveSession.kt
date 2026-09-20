package com.edde746.plezy.xr

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.edde746.plezy.MainActivity
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

  @JvmStatic
  external fun nativeOsdSurface(): Surface?

  @JvmStatic
  external fun nativeSetOsdVisible(visible: Boolean)

  /**
   * Shows the control bar and starts the countdown to hiding it again.
   *
   * Auto-hiding matters more here than on a screen: the bar floats in the
   * room, so leaving it up puts a permanent object in the viewer's field of
   * view for a two-hour film.
   */
  private val osdHandler = Handler(Looper.getMainLooper())
  private var osdHideRunnable: Runnable? = null
  private const val OSD_VISIBLE_MS = 4_000L

  @Volatile
  private var lastStatus: ImmersivePlaybackStatus? = null

  @Synchronized
  fun showOsd(status: ImmersivePlaybackStatus, autoHide: Boolean = true) {
    lastStatus = status
    val surface = nativeOsdSurface() ?: return
    ImmersiveOsd.draw(surface, status)
    nativeSetOsdVisible(true)
    osdHideRunnable?.let(osdHandler::removeCallbacks)
    if (!autoHide) return
    val hide = Runnable { nativeSetOsdVisible(false) }
    osdHideRunnable = hide
    osdHandler.postDelayed(hide, OSD_VISIBLE_MS)
  }

  /** Redraws without disturbing the hide countdown, for ticking the clock. */
  @Synchronized
  fun updateOsd(status: ImmersivePlaybackStatus) {
    lastStatus = status
    val surface = nativeOsdSurface() ?: return
    ImmersiveOsd.draw(surface, status)
  }

  @Synchronized
  fun hideOsd() {
    osdHideRunnable?.let(osdHandler::removeCallbacks)
    osdHideRunnable = null
    nativeSetOsdVisible(false)
  }

  private var loaded = false

  /**
   * Notified when the runtime ends the session -- the Meta button, the headset
   * coming off, the system menu. The activity has to finish itself then:
   * otherwise the frame loop stops while the activity stays up showing
   * nothing, and the only way out is force-killing the app.
   */
  @Volatile
  var onSessionEnded: (() -> Unit)? = null

  /** Called from the session thread in immersive_session.cpp. */
  @JvmStatic
  fun onSessionEndedFromNative() {
    onSessionEnded?.invoke()
  }

  // Mirrors the action codes in immersive_session.cpp.
  const val INPUT_PLAY_PAUSE = 1
  const val INPUT_SEEK_BACKWARD = 2
  const val INPUT_SEEK_FORWARD = 3
  const val INPUT_EXIT = 4

  /**
   * Controller input, called from the session thread.
   *
   * Playback actions go to Dart rather than straight to the player core: Dart
   * owns the playback state, the progress reporting and the panel's own
   * controls, and driving the core directly behind its back would leave all
   * three disagreeing about whether the film is playing.
   */
  @JvmStatic
  fun onInputFromNative(action: Int) {
    if (action == INPUT_EXIT) {
      onSessionEnded?.invoke()
      return
    }
    val method = when (action) {
      INPUT_PLAY_PAUSE -> "playPause"
      INPUT_SEEK_BACKWARD -> "seekBackward"
      INPUT_SEEK_FORWARD -> "seekForward"
      else -> return
    }
    // Any press brings the bar back: pressing a button and seeing nothing
    // change is the worst outcome when there is no other feedback.
    lastStatus?.let { showOsd(it) }
    val channel = MainActivity.immersiveInputChannel ?: return
    Handler(Looper.getMainLooper()).post {
      try {
        channel.invokeMethod(method, null)
      } catch (error: Throwable) {
        Log.w(TAG, "could not deliver immersive input: $method", error)
      }
    }
  }

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
    ImmersiveSession.onSessionEnded = { runOnUiThread { returnToPanel() } }

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

  /**
   * Gives the viewer back to the 2D panel.
   *
   * The panel activity was never finished -- it owns the FlutterEngine and the
   * player -- so it is still sitting in its task and only needs bringing
   * forward. Leaving without this drops the viewer into the shell with Plezy
   * apparently gone, which is what made exiting feel broken.
   */
  private fun returnToPanel() {
    if (isFinishing || isDestroyed) return
    try {
      startActivity(
        Intent(this, MainActivity::class.java).apply {
          action = Intent.ACTION_MAIN
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
      )
    } catch (error: Throwable) {
      Log.w(TAG, "could not bring the panel back", error)
    }
    finish()
  }

  override fun onDestroy() {
    super.onDestroy()
    ImmersiveSession.onSessionEnded = null
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
