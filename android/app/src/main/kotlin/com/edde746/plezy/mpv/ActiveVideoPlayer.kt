package com.edde746.plezy.mpv

/**
 * The video player core currently owned by the Flutter engine, if any.
 *
 * The immersive player runs in its own activity and has no route to the
 * engine's plugins, but it must drive the same core: Plezy's track selection,
 * resume position and progress reporting all live above it, and a second
 * player would have to re-implement every one of them and then drift from
 * upstream. A single reference keeps immersive playback a change of output
 * surface rather than a change of player.
 *
 * Audio-only cores never register -- they have no video output to redirect.
 */
internal object ActiveVideoPlayer {
  @Volatile
  private var core: MpvPlayerCore? = null

  fun register(candidate: MpvPlayerCore) {
    core = candidate
  }

  /** Identity-guarded: a disposed core must not clear its successor. */
  fun unregister(candidate: MpvPlayerCore) {
    if (core === candidate) core = null
  }

  fun current(): MpvPlayerCore? = core
}
