import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'app_logger.dart';
import 'device_channel.dart';
import 'platform_detector.dart';

/// Whether playback has been handed over to the headset's immersive player.
///
/// The flag is owned here rather than reported by the immersive activity
/// because of the order Android runs the transition in: the panel activity's
/// `onPause` -- and therefore the Dart lifecycle handler that decides whether
/// to pause playback -- runs *before* the immersive activity's `onCreate`.
/// Anything the immersive side set would arrive after that decision had already
/// been made, and playback would pause every time it was handed over.
///
/// So the hand-off starts here: set the flag, then start the activity.
class ImmersivePlayback {
  ImmersivePlayback._();

  static bool _active = false;

  /// True while the picture belongs to the headset rather than the panel.
  ///
  /// Read by the player's lifecycle handling: backgrounding the panel during a
  /// hand-off is not the viewer leaving, so playback must continue.
  static bool get isActive => _active;

  /// Hands playback to the immersive player. Returns false when it could not
  /// start, leaving playback where it is.
  static Future<bool> start() async {
    if (!PlatformDetector.isVR()) return false;
    if (_active) return true;
    // Set before the activity starts, not after: see the note above.
    _active = true;
    try {
      final started = await deviceChannel.invokeMethod<bool>('startImmersivePlayback') ?? false;
      if (!started) _active = false;
      return started;
    } on PlatformException catch (error, stackTrace) {
      _active = false;
      appLogger.w('Could not start immersive playback', error: error, stackTrace: stackTrace);
      return false;
    } on MissingPluginException {
      _active = false;
      return false;
    }
  }

  /// Called when the panel is in charge of the picture again.
  static void markEnded() {
    _active = false;
  }

  @visibleForTesting
  static void debugSetActive(bool value) {
    _active = value;
  }
}
