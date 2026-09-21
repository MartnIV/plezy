package com.edde746.plezy.xr

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

  private val backgroundPaint = Paint().apply {
    isAntiAlias = true
    color = Color.argb(200, 12, 12, 16)
  }
  private val trackPaint = Paint().apply {
    isAntiAlias = true
    color = Color.argb(150, 90, 90, 100)
  }
  private val progressPaint = Paint().apply {
    isAntiAlias = true
    color = Color.rgb(120, 170, 255)
  }
  private val textPaint = Paint().apply {
    isAntiAlias = true
    color = Color.WHITE
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
  fun draw(target: Surface, status: ImmersivePlaybackStatus, notice: String? = null) {
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

      val radius = h * 0.22f
      canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, backgroundPaint)

      val padding = h * 0.18f
      val glyphSize = h * 0.30f
      val glyphCentreX = padding + glyphSize * 0.6f
      val glyphCentreY = h * 0.42f
      if (status.isPlaying) {
        // Pause: two bars.
        val barWidth = glyphSize * 0.28f
        val gap = glyphSize * 0.22f
        val top = glyphCentreY - glyphSize / 2f
        val bottom = glyphCentreY + glyphSize / 2f
        canvas.drawRect(glyphCentreX - gap / 2f - barWidth, top, glyphCentreX - gap / 2f, bottom, glyphPaint)
        canvas.drawRect(glyphCentreX + gap / 2f, top, glyphCentreX + gap / 2f + barWidth, bottom, glyphPaint)
      } else {
        // Play: a triangle.
        val path = android.graphics.Path().apply {
          moveTo(glyphCentreX - glyphSize * 0.3f, glyphCentreY - glyphSize / 2f)
          lineTo(glyphCentreX - glyphSize * 0.3f, glyphCentreY + glyphSize / 2f)
          lineTo(glyphCentreX + glyphSize * 0.45f, glyphCentreY)
          close()
        }
        canvas.drawPath(path, glyphPaint)
      }

      textPaint.textSize = h * 0.20f
      textPaint.textAlign = Paint.Align.LEFT
      val titleX = glyphCentreX + glyphSize
      val headline = notice ?: status.title
      if (notice != null) textPaint.color = Color.rgb(150, 200, 255)
      val title = ellipsize(headline, textPaint, w - titleX - padding * 6f)
      canvas.drawText(title, titleX, h * 0.36f, textPaint)
      textPaint.color = Color.WHITE

      textPaint.textSize = h * 0.17f
      textPaint.textAlign = Paint.Align.RIGHT
      val clock = "${formatTime(status.positionMs)} / ${formatTime(status.durationMs)}"
      canvas.drawText(clock, w - padding, h * 0.36f, textPaint)

      val barTop = h * 0.60f
      val barHeight = h * 0.10f
      val barLeft = padding
      val barRight = w - padding
      val barRadius = barHeight / 2f
      canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barTop + barHeight), barRadius, barRadius, trackPaint)
      if (status.durationMs > 0) {
        val fraction = (status.positionMs.toFloat() / status.durationMs).coerceIn(0f, 1f)
        val filledRight = barLeft + (barRight - barLeft) * fraction
        if (filledRight > barLeft) {
          canvas.drawRoundRect(
            RectF(barLeft, barTop, filledRight, barTop + barHeight),
            barRadius,
            barRadius,
            progressPaint,
          )
        }
      }
    } finally {
      try {
        target.unlockCanvasAndPost(canvas)
      } catch (error: Throwable) {
        Log.w(TAG, "could not post the OSD frame", error)
      }
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
