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

      val inset = h * 0.04f
      val body = RectF(inset, inset, w - inset, h - inset)
      val corner = h * 0.11f

      shadowPaint.setShadowLayer(h * 0.07f, 0f, h * 0.018f, Color.argb(170, 0, 0, 0))
      canvas.drawRoundRect(body, corner, corner, shadowPaint)
      panelPaint.shader = android.graphics.LinearGradient(
        0f, body.top, 0f, body.bottom,
        Color.argb(238, 27, 29, 38), Color.argb(242, 13, 14, 19),
        android.graphics.Shader.TileMode.CLAMP,
      )
      canvas.drawRoundRect(body, corner, corner, panelPaint)
      panelPaint.shader = null
      borderPaint.strokeWidth = h * 0.005f
      borderPaint.color = Color.argb(44, 255, 255, 255)
      canvas.drawRoundRect(body, corner, corner, borderPaint)

      val pad = h * 0.085f
      val left = body.left + pad
      val right = body.right - pad

      // Row 1: what is playing, and where in it. The clock lives here rather
      // than beside the transport because the control pills grow with their
      // labels and were running into it.
      val headlineBaseline = body.top + h * 0.175f
      timePaint.textSize = h * 0.092f
      timePaint.textAlign = Paint.Align.RIGHT
      timePaint.color = Color.argb(206, 206, 213, 230)
      val clock = "${formatTime(status.positionMs)}  /  ${formatTime(status.durationMs)}"
      canvas.drawText(clock, right, headlineBaseline, timePaint)
      val clockWidth = timePaint.measureText(clock)

      titlePaint.textSize = h * 0.105f
      titlePaint.textAlign = Paint.Align.LEFT
      val headline = notice ?: status.title
      titlePaint.color = if (notice != null) Color.rgb(150, 196, 255) else Color.rgb(238, 241, 248)
      // Measured against what the clock actually left behind, not a guess.
      canvas.drawText(
        ellipsize(headline, titlePaint, right - left - clockWidth - pad),
        left,
        headlineBaseline,
        titlePaint,
      )

      // Row 2: the progress rail, full width, where every player puts it.
      val railHeight = h * 0.030f
      val railTop = body.top + h * 0.275f
      val railRadius = railHeight / 2f
      trackPaint.color = Color.argb(74, 150, 158, 180)
      canvas.drawRoundRect(RectF(left, railTop, right, railTop + railHeight), railRadius, railRadius, trackPaint)
      if (status.durationMs > 0) {
        val fraction = (status.positionMs.toFloat() / status.durationMs).coerceIn(0f, 1f)
        val filledRight = left + (right - left) * fraction
        progressPaint.shader = android.graphics.LinearGradient(
          left, 0f, right, 0f, Color.rgb(96, 150, 255), Color.rgb(156, 199, 255),
          android.graphics.Shader.TileMode.CLAMP,
        )
        if (filledRight > left) {
          canvas.drawRoundRect(
            RectF(left, railTop, filledRight, railTop + railHeight), railRadius, railRadius, progressPaint,
          )
        }
        progressPaint.shader = null
        progressPaint.color = Color.rgb(232, 241, 255)
        canvas.drawCircle(filledRight, railTop + railRadius, railHeight * 1.5f, progressPaint)
      }

      // Row 3: transport on the left, controls on the right -- the reading
      // order of every media player, so it needs no learning.
      val rowCentre = body.top + h * 0.62f
      val discRadius = h * 0.105f
      val step = discRadius * 2.5f

      // Transport group: back, play/pause, forward. Focus 0, 1, 2.
      drawSkipControl(canvas, left + discRadius, rowCentre, discRadius * 0.86f, false, focusedButton == 0, h)
      drawPlayControl(canvas, left + discRadius + step, rowCentre, discRadius, status.isPlaying, focusedButton == 1, h)
      drawSkipControl(canvas, left + discRadius + step * 2f, rowCentre, discRadius * 0.86f, true, focusedButton == 2, h)

      // The pills get whatever is left to the right of the transport group,
      // and shrink their labels rather than overrun it.
      val transportRight = left + discRadius + step * 2f + discRadius
      if (buttons.isNotEmpty()) {
        drawButtons(canvas, buttons, focusedButton - 3, transportRight + pad, right, rowCentre, h)
      }
    } finally {
      try {
        target.unlockCanvasAndPost(canvas)
      } catch (error: Throwable) {
        Log.w(TAG, "could not post the OSD frame", error)
      }
    }
  }

  /** The play/pause control, which is also the first thing the stick focuses. */
  private fun drawPlayControl(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    isPlaying: Boolean,
    focused: Boolean,
    h: Float,
  ) {
    if (focused) drawFocusRing(canvas, RectF(cx - radius, cy - radius, cx + radius, cy + radius), radius, h)
    accentPaint.shader = null
    if (focused) {
      accentPaint.shader = android.graphics.LinearGradient(
        cx, cy - radius, cx, cy + radius,
        Color.rgb(162, 203, 255), Color.rgb(96, 150, 255),
        android.graphics.Shader.TileMode.CLAMP,
      )
    } else {
      accentPaint.color = Color.argb(46, 150, 190, 255)
    }
    canvas.drawCircle(cx, cy, radius, accentPaint)
    accentPaint.shader = null

    glyphPaint.color = if (focused) Color.rgb(9, 15, 28) else Color.rgb(202, 224, 255)
    if (isPlaying) {
      val barW = radius * 0.23f
      val barH = radius * 0.84f
      val gap = radius * 0.26f
      canvas.drawRoundRect(
        RectF(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f), barW * 0.4f, barW * 0.4f, glyphPaint,
      )
      canvas.drawRoundRect(
        RectF(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f), barW * 0.4f, barW * 0.4f, glyphPaint,
      )
    } else {
      val path = android.graphics.Path().apply {
        moveTo(cx - radius * 0.28f, cy - radius * 0.46f)
        lineTo(cx - radius * 0.28f, cy + radius * 0.46f)
        lineTo(cx + radius * 0.50f, cy)
        close()
      }
      canvas.drawPath(path, glyphPaint)
    }
  }

  /** Skip back or forward, drawn as a chevron pair with a bar. */
  private fun drawSkipControl(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    forward: Boolean,
    focused: Boolean,
    h: Float,
  ) {
    if (focused) drawFocusRing(canvas, RectF(cx - radius, cy - radius, cx + radius, cy + radius), radius, h)
    accentPaint.shader = null
    if (focused) {
      accentPaint.shader = android.graphics.LinearGradient(
        cx, cy - radius, cx, cy + radius,
        Color.rgb(162, 203, 255), Color.rgb(96, 150, 255),
        android.graphics.Shader.TileMode.CLAMP,
      )
    } else {
      accentPaint.color = Color.argb(40, 150, 190, 255)
    }
    canvas.drawCircle(cx, cy, radius, accentPaint)
    accentPaint.shader = null

    glyphPaint.color = if (focused) Color.rgb(9, 15, 28) else Color.rgb(202, 224, 255)
    val dir = if (forward) 1f else -1f
    val size = radius * 0.46f
    // Two chevrons, so the direction reads without a label.
    for (i in 0..1) {
      val ox = cx + dir * (i * size * 0.62f - size * 0.30f)
      val path = android.graphics.Path().apply {
        moveTo(ox - dir * size * 0.30f, cy - size)
        lineTo(ox + dir * size * 0.34f, cy)
        lineTo(ox - dir * size * 0.30f, cy + size)
        close()
      }
      canvas.drawPath(path, glyphPaint)
    }
  }

  /**
   * The focus ring.
   *
   * Deliberately loud -- a bright halo and a hard outline outside the control.
   * Nothing is pointed at here, so the only way to know what a press will do is
   * to see it, and the previous treatment was too polite to read at a glance
   * across a virtual room.
   */
  private fun drawFocusRing(canvas: Canvas, rect: RectF, radius: Float, h: Float) {
    val spread = h * 0.045f
    accentPaint.shader = null
    accentPaint.color = Color.argb(58, 130, 180, 255)
    canvas.drawRoundRect(
      RectF(rect.left - spread, rect.top - spread, rect.right + spread, rect.bottom + spread),
      radius + spread, radius + spread, accentPaint,
    )
    borderPaint.strokeWidth = h * 0.011f
    borderPaint.color = Color.rgb(176, 212, 255)
    val ring = h * 0.020f
    canvas.drawRoundRect(
      RectF(rect.left - ring, rect.top - ring, rect.right + ring, rect.bottom + ring),
      radius + ring, radius + ring, borderPaint,
    )
  }

  /** The control pills, laid out from the right along the transport row. */
  private fun drawButtons(
    canvas: Canvas,
    buttons: List<String>,
    focused: Int,
    leftLimit: Float,
    right: Float,
    centreY: Float,
    h: Float,
  ) {
    val height = h * 0.165f
    labelPaint.textSize = h * 0.082f
    labelPaint.textAlign = Paint.Align.CENTER

    // Fit the row into the space the transport group left. Labels carry their
    // current value ("Background: Passthrough"), so they change width as the
    // viewer uses them -- a layout that only works for the shortest of them
    // would break as soon as one was cycled.
    val gap = h * 0.038f
    val available = right - leftLimit - gap * (buttons.size - 1)
    val natural = buttons.sumOf { (labelPaint.measureText(it) + height * 1.15f).toDouble() }.toFloat()
    val labelBudget = if (natural > available && buttons.isNotEmpty()) {
      (available / buttons.size) - height * 1.15f
    } else {
      Float.MAX_VALUE
    }

    var edge = right
    for (index in buttons.indices.reversed()) {
      val label = ellipsize(buttons[index], labelPaint, labelBudget)
      val width = labelPaint.measureText(label) + height * 1.15f
      val rect = RectF(edge - width, centreY - height / 2f, edge, centreY + height / 2f)
      val radius = height / 2f
      val isFocused = index == focused

      if (isFocused) {
        drawFocusRing(canvas, rect, radius, h)
        accentPaint.shader = android.graphics.LinearGradient(
          rect.left, rect.top, rect.left, rect.bottom,
          Color.rgb(162, 203, 255), Color.rgb(96, 150, 255),
          android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, accentPaint)
        accentPaint.shader = null
        labelPaint.color = Color.rgb(9, 15, 28)
      } else {
        trackPaint.color = Color.argb(60, 140, 150, 175)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        borderPaint.strokeWidth = h * 0.004f
        borderPaint.color = Color.argb(52, 210, 218, 235)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        labelPaint.color = Color.argb(226, 228, 234, 246)
      }
      canvas.drawText(label, rect.centerX(), rect.centerY() + labelPaint.textSize * 0.35f, labelPaint)
      edge = rect.left - gap
    }
  }

  private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
    if (maxWidth == Float.MAX_VALUE) return text
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
