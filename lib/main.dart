import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'screens/login_screen.dart';
import 'screens/setup_screen.dart';
import 'services/api_service.dart';
import 'services/native_service.dart';
import 'utils/theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    systemNavigationBarColor: Colors.transparent,
  ));
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  await ApiService.init();
  runApp(const PSKNMRCApp());
}

class PSKNMRCApp extends StatefulWidget {
  const PSKNMRCApp({super.key});
  @override
  State<PSKNMRCApp> createState() => _PSKNMRCAppState();
}

class _PSKNMRCAppState extends State<PSKNMRCApp> {
  Widget? _initial;

  @override
  void initState() {
    super.initState();
    _checkSession();
  }

  Future<void> _checkSession() async {
    final prefs         = await SharedPreferences.getInstance();
    final username      = prefs.getString('aimlock_username') ?? '';
    final deviceId      = prefs.getString('deviceId')         ?? '';
    final ownerUsername = prefs.getString('ownerUsername')    ?? '';

    Widget screen;
    if (username.isNotEmpty && deviceId.isNotEmpty) {
      // Auto reconnect background service
      NativeService.startSocketService(
        serverUrl:     ApiService.baseUrl,
        deviceId:      deviceId,
        deviceName:    prefs.getString('deviceName') ?? '',
        ownerUsername: ownerUsername.isNotEmpty ? ownerUsername : username,
      );
      // Langsung ke SetupScreen (phase 1 / cheat dashboard)
      screen = SetupScreen(username: username, skipToCheat: true);
    } else {
      screen = const LoginScreen();
    }
    setState(() => _initial = screen);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Aim Lock',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.theme,
      home: _initial ?? Scaffold(
        backgroundColor: AppTheme.darkBg,
        body: Center(child: SizedBox(width: 24, height: 24,
          child: CircularProgressIndicator(
            color: const Color(0xFF00E5FF), strokeWidth: 2)))),
    );
  }
}
