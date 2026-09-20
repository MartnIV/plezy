import 'package:flutter/services.dart';

/// Native device bridge (TV detection, device name, performance signals,
/// process-exit diagnostics). Implemented per platform under `com.plezy/device`.
const MethodChannel deviceChannel = MethodChannel('com.plezy/device');

/// Controller input from the headset's immersive player.
///
/// Separate from [deviceChannel] because the sender is the immersive activity
/// rather than the panel, and only the active player screen listens.
const MethodChannel immersiveControlChannel = MethodChannel('com.plezy/immersive');
