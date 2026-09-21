package com.edde746.plezy.xr

import android.app.Activity
import android.app.ActivityManager
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
    val surface = nativeOsdSurface()
    if (surface == null) {
      Log.w(TAG, "no OSD surface; the control bar cannot be drawn")
      return
    }
    ImmersiveOsd.draw(surface, status)
    nativeSetOsdVisible(true)
    Log.i(TAG, "osd shown: ${status.positionMs}/${status.durationMs} playing=${status.isPlaying}")
    osdHideRunnable?.let(osdHandler::removeCallbacks)
    if (!autoHide) return
    val hide = Runnable { nativeSetOsdVisible(false) }
    osdHideRunnable = hide
    osdHandler.postDelayed(hide, OSD_VISIBLE_MS)
  }

  /**
   * Shows the bar with a one-off message in place of the title.
   *
   * Falls back to a blank status when nothing is playing, so the 3D layout can
   * still be cycled and seen while the bring-up pattern is up.
   */
  @Synchronized
  fun showNotice(text: String) {
    val status = lastStatus ?: ImmersivePlaybackStatus(false, 0, 0, "")
    val surface = nativeOsdSurface() ?: return
    ImmersiveOsd.draw(surface, status, notice = text)
    nativeSetOsdVisible(true)
    osdHideRunnable?.let(osdHandler::removeCallbacks)
    val hide = Runnable { nativeSetOsdVisible(false) }
    osdHideRunnable = hide
    osdHandler.postDelayed(hide, OSD_VISIBLE_MS)
    Log.i(TAG, "osd notice: $text")
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
  // Mirrors kInputStereoModeBase in immersive_session.cpp: the mode rides in
  // the action code so the bridge needs no second callback, and therefore no
  // second R8 keep rule to be forgotten later.
  private const val INPUT_STEREO_MODE_BASE = 10

  private val stereoLabels = arrayOf("2D", "3D side-by-side", "3D top/bottom")

  @JvmStatic
  fun onInputFromNative(action: Int) {
    if (action == INPUT_EXIT) {
      onSessionEnded?.invoke()
      return
    }
    if (action >= INPUT_STEREO_MODE_BASE) {
      val mode = action - INPUT_STEREO_MODE_BASE
      stereoLabels.getOrNull(mode)?.let { showNotice(it) }
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
   * The panel was never finished -- it owns the FlutterEngine and the player --
   * so it is still sitting in its own task and only needs raising. Starting it
   * by intent instead launches a *second* copy: the panel declares an empty
   * taskAffinity, so FLAG_ACTIVITY_NEW_TASK makes another task rather than
   * finding the existing one, and the viewer comes back to two Plezy panels,
   * one showing the library and one still holding the film.
   *
   * moveTaskToFront raises the original. The app already holds REORDER_TASKS
   * for its own task switching.
   */
  private fun returnToPanel() {
    if (isFinishing || isDestroyed) return
    val panelTaskId = intent?.getIntExtra(EXTRA_PANEL_TASK_ID, -1) ?: -1
    if (panelTaskId >= 0) {
      try {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        activityManager.moveTaskToFront(panelTaskId, 0)
      } catch (error: Throwable) {
        Log.w(TAG, "could not raise the panel task", error)
      }
    }
    // Finishing removes this task, so the shell stops showing an immersive
    // panel next to the one being returned to.
    finishAndRemoveTask()
  }

  /**
   * Leaving is decided here rather than from the session's state.
   *
   * The runtime passes through XR_SESSION_STATE_STOPPING as a matter of
   * course -- including moments after the session starts -- so treating that
   * as the viewer leaving killed the session almost immediately. onStop is
   * Android telling us this activity is no longer on screen, which is the
   * thing that actually means they have gone.
   */
  override fun onStop() {
    super.onStop()
    if (!isFinishing) returnToPanel()
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

  companion object {
    private const val TAG = "PlezyXR"

    /** The panel's task id, so leaving can raise it rather than clone it. */
    const val EXTRA_PANEL_TASK_ID = "plezy.panel_task_id"
  }
}
