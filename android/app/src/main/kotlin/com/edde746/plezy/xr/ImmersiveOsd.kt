package com.edde746.plezy.xr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import java.util.Locale

/** What the control bar shows. Pushed from Dart, which owns playback state. */
data class ImmersivePlaybackStatus(
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val title: String,
)

/**
 * Draws the immersive control bar into its own swapchain surface.
 *
 * Deliberately a drawn bar rather than the app's Flutter controls rendered into
 * a layer: the Flutter UI lives in the panel activity's engine, and getting it
 * onto a second surface would mean a virtual display plus synthesising ray
 * input into touches. What a viewer needs mid-film is where they are and
 * whether it is playing, and that is cheap to draw directly.
 *
 * Text is sized for reading at arm's length through lenses, which is why it is
 * far larger relative to the bar than a screen OSD would be.
 */
object ImmersiveOsd {
  private const val TAG = "PlezyXR"
  private const val SPACE_ASSET = "space_background.jpg"

  private val panelPaint = Paint().apply { isAntiAlias = true }
  private val shadowPaint = Paint().apply {
    isAntiAlias = true
    color = Color.argb(200, 8, 9, 14)
  }
  private val borderPaint = Paint().apply {
    isAntiAlias = true
    style = Paint.Style.STROKE
  }
  private val accentPaint = Paint().apply { isAntiAlias = true }
  private val titlePaint = Paint().apply {
    isAntiAlias = true
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
  }
  private val timePaint = Paint().apply {
    isAntiAlias = true
    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
  }
  private val labelPaint = Paint().apply {
    isAntiAlias = true
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
  }
  private val trackPaint = Paint().apply {
    isAntiAlias = true
    color = Color.argb(150, 90, 90, 100)
  }
  private val progressPaint = Paint().apply {
    isAntiAlias = true
    color = Color.rgb(120, 170, 255)
  }

  private val glyphPaint = Paint().apply {
    isAntiAlias = true
    color = Color.WHITE
  }

  /**
   * [notice] replaces the title line for a moment after something changes that
   * has no other visible effect -- the 3D layout above all, which otherwise
   * has to be cycled blind and judged by whether the picture looks wrong.
   */
  /**
   * Paints a starfield for the equirect background layer.
   *
   * Drawn once, not animated: a cinema needs a quiet backdrop, and anything
   * that moves in the corner of the eye competes with the film. Stars thin out
   * toward the poles because the equirect projection stretches those rows
   * enormously, and an even scatter here would clump into two bright caps
   * overhead and underfoot.
   */
  /**
   * Paints the space background from the bundled equirectangular panorama.
   *
   * A real photograph rather than procedural shapes: gradients and dots can
   * suggest a sky but never carry the dust lanes, clustering and fine
   * structure that make one look real, which is why the drawn version kept
   * reading as cheap however much detail was piled into it.
   *
   * The asset is ESO/S. Brunier's Milky Way panorama under CC BY 4.0, colour
   * graded toward pink and darkened; see the attribution file beside it. It is
   * already 360x180 equirectangular, so it maps straight onto the background
   * layer with no reprojection.
   */
  fun drawStarfield(target: Surface, context: Context) {
    val bitmap = try {
      context.assets.open(SPACE_ASSET).use { stream ->
        // 565 halves the decode: the panorama has no alpha, and at this size
        // the full-colour copy is tens of megabytes held only to be blitted
        // once and thrown away.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeStream(stream, null, options)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "could not decode the space background", error)
      null
    }
    if (bitmap == null) {
      Log.w(TAG, "no space background; leaving the sky black")
      return
    }

    val canvas: Canvas = try {
      target.lockCanvas(null)
    } catch (error: Throwable) {
      Log.w(TAG, "could not lock the starfield surface", error)
      bitmap.recycle()
      return
    }
    try {
      canvas.drawColor(Color.BLACK)
      val paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
      }
      canvas.drawBitmap(
        bitmap,
        null,
        RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
        paint,
      )
    } finally {
      try {
        target.unlockCanvasAndPost(canvas)
      } catch (error: Throwable) {
        Log.w(TAG, "could not post the starfield", error)
      }
      bitmap.recycle()
    }
    Log.i(TAG, "space background drawn")
  }

  fun draw(
    target: Surface,
    status: ImmersivePlaybackStatus,
    notice: String? = null,
    buttons: List<String> = emptyList(),
    focusedButton: Int = -1,
  ) {
    val canvas: Canvas = try {
      target.lockCanvas(null)
    } catch (error: Throwable) {
      Log.w(TAG, "could not lock the OSD surface", error)
      return
    }
    try {
      val w = canvas.width.toFloat()
      val h = canvas.height.toFloat()
      // Every pixel is rewritten each time: the swapchain hands back whichever
      // buffer is free, which still holds an older frame's content.
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

      val inset = h * 0.06f
      val body = RectF(inset, inset, w - inset, h - inset)
      val radius = body.height() * 0.28f

      // A soft drop shadow lifts the bar off whatever is behind it. Against
      // passthrough especially, an unshadowed panel looks pasted on.
      shadowPaint.setShadowLayer(h * 0.10f, 0f, h * 0.025f, Color.argb(160, 0, 0, 0))
      canvas.drawRoundRect(body, radius, radius, shadowPaint)

      // Vertical gradient rather than a flat fill: flat panels are exactly
      // what reads as unfinished.
      panelPaint.shader = android.graphics.LinearGradient(
        0f, body.top, 0f, body.bottom,
        Color.argb(234, 26, 28, 36), Color.argb(238, 14, 15, 20),
        android.graphics.Shader.TileMode.CLAMP,
      )
      canvas.drawRoundRect(body, radius, radius, panelPaint)
      panelPaint.shader = null

      // A hairline top edge, the way a lit surface catches light.
      borderPaint.strokeWidth = h * 0.008f
      borderPaint.color = Color.argb(46, 255, 255, 255)
      canvas.drawRoundRect(body, radius, radius, borderPaint)

      val padding = h * 0.16f
      val left = body.left + padding
      val right = body.right - padding
      val titleBaseline = body.top + h * 0.30f

      // Play state, as a filled disc so it reads at a glance from across a
      // virtual room rather than as a bare glyph.
      val discRadius = h * 0.115f
      val discX = left + discRadius
      val discY = body.top + h * 0.26f
      accentPaint.color = Color.argb(38, 150, 190, 255)
      canvas.drawCircle(discX, discY, discRadius, accentPaint)
      glyphPaint.color = Color.rgb(196, 220, 255)
      if (status.isPlaying) {
        val barW = discRadius * 0.24f
        val barH = discRadius * 0.86f
        val gap = discRadius * 0.24f
        canvas.drawRoundRect(
          RectF(discX - gap / 2f - barW, discY - barH / 2f, discX - gap / 2f, discY + barH / 2f),
          barW * 0.4f, barW * 0.4f, glyphPaint,
        )
        canvas.drawRoundRect(
          RectF(discX + gap / 2f, discY - barH / 2f, discX + gap / 2f + barW, discY + barH / 2f),
          barW * 0.4f, barW * 0.4f, glyphPaint,
        )
      } else {
        val path = android.graphics.Path().apply {
          moveTo(discX - discRadius * 0.30f, discY - discRadius * 0.48f)
          lineTo(discX - discRadius * 0.30f, discY + discRadius * 0.48f)
          lineTo(discX + discRadius * 0.52f, discY)
          close()
        }
        canvas.drawPath(path, glyphPaint)
      }

      val textLeft = discX + discRadius + padding * 0.75f

      // The clock is measured first so the title can be told how much room is
      // actually left, instead of being ellipsized against a guess.
      timePaint.textSize = h * 0.115f
      timePaint.textAlign = Paint.Align.RIGHT
      timePaint.color = Color.argb(205, 208, 214, 230)
      val clock = "${formatTime(status.positionMs)}  /  ${formatTime(status.durationMs)}"
      val clockWidth = timePaint.measureText(clock)
      canvas.drawText(clock, right, titleBaseline, timePaint)

      titlePaint.textSize = h * 0.145f
      titlePaint.textAlign = Paint.Align.LEFT
      val headline = notice ?: status.title
      titlePaint.color = if (notice != null) Color.rgb(150, 196, 255) else Color.rgb(242, 244, 250)
      val available = right - clockWidth - padding - textLeft
      canvas.drawText(ellipsize(headline, titlePaint, available), textLeft, titleBaseline, titlePaint)

      // Progress, as a thin rounded rail with a knob -- the knob is what makes
      // the position readable at a distance.
      val railHeight = h * 0.042f
      val railTop = body.top + h * 0.46f
      val railRadius = railHeight / 2f
      trackPaint.color = Color.argb(70, 150, 158, 180)
      canvas.drawRoundRect(RectF(left, railTop, right, railTop + railHeight), railRadius, railRadius, trackPaint)
      if (status.durationMs > 0) {
        val fraction = (status.positionMs.toFloat() / status.durationMs).coerceIn(0f, 1f)
        val filledRight = left + (right - left) * fraction
        progressPaint.shader = android.graphics.LinearGradient(
          left, 0f, right, 0f,
          Color.rgb(96, 150, 255), Color.rgb(150, 196, 255),
          android.graphics.Shader.TileMode.CLAMP,
        )
        if (filledRight > left) {
          canvas.drawRoundRect(
            RectF(left, railTop, filledRight, railTop + railHeight), railRadius, railRadius, progressPaint,
          )
        }
        progressPaint.shader = null
        progressPaint.color = Color.rgb(226, 238, 255)
        canvas.drawCircle(filledRight, railTop + railRadius, railHeight * 1.05f, progressPaint)
      }

      if (buttons.isNotEmpty()) drawButtons(canvas, buttons, focusedButton, left, right, body.bottom, h)
    } finally {
      try {
        target.unlockCanvasAndPost(canvas)
      } catch (error: Throwable) {
        Log.w(TAG, "could not post the OSD frame", error)
      }
    }
  }

  /**
   * The button row, laid out from the right along the bar's lower edge.
   *
   * Nothing here is pointed at, so there is no hover state: the focused button
   * is filled and ringed, the rest are quiet outlines that do not compete with
   * the film.
   */
  private fun drawButtons(
    canvas: Canvas,
    buttons: List<String>,
    focused: Int,
    left: Float,
    right: Float,
    bottom: Float,
    h: Float,
  ) {
    val height = h * 0.185f
    val top = bottom - height - h * 0.10f
    labelPaint.textSize = h * 0.098f
    labelPaint.textAlign = Paint.Align.CENTER

    var edge = right
    for (index in buttons.indices.reversed()) {
      val label = buttons[index]
      val width = labelPaint.measureText(label) + height * 1.25f
      val rect = RectF(edge - width, top, edge, top + height)
      val radius = height / 2f
      val isFocused = index == focused

      if (isFocused) {
        // A halo outside the pill, so the focused control is obvious without
        // the bar having to get louder overall.
        accentPaint.color = Color.argb(60, 120, 170, 255)
        canvas.drawRoundRect(
          RectF(rect.left - h * 0.022f, rect.top - h * 0.022f, rect.right + h * 0.022f, rect.bottom + h * 0.022f),
          radius + h * 0.022f, radius + h * 0.022f, accentPaint,
        )
        accentPaint.shader = android.graphics.LinearGradient(
          rect.left, rect.top, rect.left, rect.bottom,
          Color.rgb(150, 196, 255), Color.rgb(92, 146, 250),
          android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, accentPaint)
        accentPaint.shader = null
        labelPaint.color = Color.rgb(10, 16, 30)
      } else {
        trackPaint.color = Color.argb(56, 140, 150, 175)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        borderPaint.strokeWidth = h * 0.006f
        borderPaint.color = Color.argb(48, 210, 218, 235)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        labelPaint.color = Color.argb(224, 226, 232, 245)
      }
      // Optical centring: text sits slightly above the geometric middle.
      canvas.drawText(label, rect.centerX(), rect.centerY() + labelPaint.textSize * 0.35f, labelPaint)
      edge = rect.left - h * 0.05f
    }
  }

  private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
    if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
    return if (end <= 0) "" else text.substring(0, end) + "…"
  }

  /** Hours only when the film has them, so most titles read as mm:ss. */
  private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
  }
}
