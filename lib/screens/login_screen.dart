import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player/video_player.dart';
import '../services/api_service.dart';
import '../utils/theme.dart';
import 'setup_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> with TickerProviderStateMixin {
  final _usernameCtrl = TextEditingController();
  bool _loading = false;

  late AnimationController _glowCtrl;
  late Animation<double> _glowAnim;
  late AnimationController _rotateCtrl;
  late Animation<double> _rotateAnim;

  late VideoPlayerController _bgCtrl;
  bool _bgReady = false;

  @override
  void initState() {
    super.initState();

    _glowCtrl = AnimationController(vsync: this, duration: const Duration(seconds: 2))
      ..repeat(reverse: true);
    _glowAnim = Tween<double>(begin: 0.3, end: 1.0).animate(
      CurvedAnimation(parent: _glowCtrl, curve: Curves.easeInOut));

    _rotateCtrl = AnimationController(vsync: this, duration: const Duration(seconds: 8))
      ..repeat();
    _rotateAnim = Tween<double>(begin: 0.0, end: 1.0).animate(_rotateCtrl);

    _bgCtrl = VideoPlayerController.asset('assets/video/background.mp4')
      ..initialize().then((_) {
        if (mounted) {
          setState(() => _bgReady = true);
          _bgCtrl.setLooping(true);
          _bgCtrl.setVolume(0);
          _bgCtrl.play();
        }
      }).catchError((_) {});
  }

  @override
  void dispose() {
    _glowCtrl.dispose();
    _rotateCtrl.dispose();
    _usernameCtrl.dispose();
    _bgCtrl.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    final username = _usernameCtrl.text.trim();
    if (username.isEmpty) {
      _snack('Username wajib diisi', error: true);
      return;
    }
    setState(() => _loading = true);
    try {
      await ApiService.init();
      final res = await ApiService.post('/api/psknmrc/login', {'username': username});
      if (res['success'] == true) {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString('aimlock_username', username);
        await prefs.setString('psknmrc_role', res['role'] ?? 'premium');
        final ownerUsername = res['ownerUsername'] as String? ?? username;
        await prefs.setString('ownerUsername', ownerUsername);
        if (!mounted) return;
        Navigator.pushReplacement(context,
          MaterialPageRoute(builder: (_) => SetupScreen(username: username)));
      } else {
        _snack(res['message'] as String? ?? 'Login gagal', error: true);
      }
    } catch (_) {
      _snack('Gagal terhubung ke server', error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  void _snack(String msg, {bool error = false}) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: const TextStyle(fontFamily: 'ShareTechMono')),
      backgroundColor: error ? Colors.red.shade800 : AppTheme.primaryBlue,
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      resizeToAvoidBottomInset: true,
      body: Stack(fit: StackFit.expand, children: [
        // ── Video BG ─────────────────────────────────────────────
        if (_bgReady)
          SizedBox.expand(child: FittedBox(fit: BoxFit.cover,
            child: SizedBox(
              width: _bgCtrl.value.size.width,
              height: _bgCtrl.value.size.height,
              child: VideoPlayer(_bgCtrl))))
        else
          Container(color: Colors.black),

        BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 4, sigmaY: 4),
          child: Container(color: Colors.transparent)),
        Container(color: Colors.black.withOpacity(0.4)),

        SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 28),
            child: Column(children: [
              const SizedBox(height: 48),

              // ── Rotating photo border ─────────────────────────
              AnimatedBuilder(
                animation: _rotateAnim,
                builder: (_, __) => Transform.rotate(
                  angle: _rotateAnim.value * 6.2832,
                  child: Container(
                    width: 120, height: 120,
                    decoration: const BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: SweepGradient(colors: [
                        Color(0xFF00E5FF), Color(0xFF1565C0),
                        Color(0xFF7C4DFF), Color(0xFF00E5FF),
                      ])),
                    child: Transform.rotate(
                      angle: -_rotateAnim.value * 6.2832,
                      child: Padding(
                        padding: const EdgeInsets.all(3),
                        child: Container(
                          decoration: const BoxDecoration(
                            shape: BoxShape.circle, color: Colors.black),
                          child: ClipOval(
                            child: Image.asset('assets/icons/login.jpg',
                              width: 114, height: 114, fit: BoxFit.cover,
                              errorBuilder: (_, __, ___) => Container(
                                color: AppTheme.primaryBlue,
                                child: const Icon(Icons.shield_rounded,
                                  color: Colors.white, size: 50)))))))),
              )),

              const SizedBox(height: 28),

              // ── PEGASUS-X CHEATER title ───────────────────────
              _buildTitle(),

              const SizedBox(height: 44),

              // ── Login card ────────────────────────────────────
              _buildLoginCard(),

              const SizedBox(height: 40),
            ]),
          ),
        ),
      ]),
    );
  }

  Widget _buildTitle() {
    return Column(children: [
      const Text('PEGASUS-X',
        textAlign: TextAlign.center,
        style: TextStyle(
          fontFamily: 'Deltha', fontSize: 34,
          fontWeight: FontWeight.w900, letterSpacing: 5, color: Colors.white)),
      const SizedBox(height: 6),

      // Glow divider
      AnimatedBuilder(
        animation: _glowAnim,
        builder: (_, __) => Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          Container(height: 1, width: 70,
            decoration: BoxDecoration(gradient: LinearGradient(colors: [
              Colors.transparent,
              const Color(0xFF00E5FF).withOpacity(_glowAnim.value)]))),
          const SizedBox(width: 8),
          Container(width: 5, height: 5,
            decoration: BoxDecoration(
              color: const Color(0xFF00E5FF).withOpacity(_glowAnim.value),
              shape: BoxShape.circle,
              boxShadow: [BoxShadow(
                color: const Color(0xFF00E5FF).withOpacity(_glowAnim.value * 0.8),
                blurRadius: 6)])),
          const SizedBox(width: 8),
          Container(height: 1, width: 70,
            decoration: BoxDecoration(gradient: LinearGradient(colors: [
              const Color(0xFF00E5FF).withOpacity(_glowAnim.value),
              Colors.transparent]))),
        ])),

      const SizedBox(height: 6),

      // Subtitle gradient
      AnimatedBuilder(
        animation: _glowAnim,
        builder: (_, __) => ShaderMask(
          shaderCallback: (bounds) => LinearGradient(colors: [
            Color.lerp(const Color(0xFF7C4DFF), const Color(0xFF00E5FF), _glowAnim.value)!,
            const Color(0xFF00E5FF),
            Color.lerp(const Color(0xFF00E5FF), const Color(0xFF7C4DFF), _glowAnim.value)!,
          ]).createShader(bounds),
          child: const Text('C · H · E · A · T · E · R',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Orbitron', fontSize: 12,
              fontWeight: FontWeight.w600, letterSpacing: 6, color: Colors.white)))),
    ]);
  }

  Widget _buildLoginCard() {
    return AnimatedBuilder(
      animation: _glowAnim,
      builder: (_, child) => Container(
        decoration: BoxDecoration(
          color: AppTheme.cardBg.withOpacity(0.75),
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color: const Color(0xFF00E5FF).withOpacity(0.15 + 0.3 * _glowAnim.value)),
          boxShadow: [BoxShadow(
            color: const Color(0xFF00E5FF).withOpacity(0.06 * _glowAnim.value),
            blurRadius: 24, spreadRadius: 2)]),
        padding: const EdgeInsets.all(24),
        child: child),
      child: Column(children: [
        // Username field
        TextFormField(
          controller: _usernameCtrl,
          style: const TextStyle(color: Colors.white, fontFamily: 'ShareTechMono'),
          decoration: InputDecoration(
            labelText: 'Username',
            prefixIcon: Padding(
              padding: const EdgeInsets.all(12),
              child: SvgPicture.string(AppSvgIcons.user,
                width: 20, height: 20,
                colorFilter: const ColorFilter.mode(AppTheme.textSecondary, BlendMode.srcIn)))),
          onFieldSubmitted: (_) => _login()),
        const SizedBox(height: 28),

        // Tombol MASUK
        GestureDetector(
          onTap: _loading ? null : _login,
          child: Container(
            width: double.infinity,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFF00E5FF), Color(0xFF1565C0)]),
              borderRadius: BorderRadius.circular(14)),
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: _loading
              ? const Center(child: SizedBox(width: 22, height: 22,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)))
              : Row(mainAxisAlignment: MainAxisAlignment.center, children: const [
                  Icon(Icons.login_rounded, color: Colors.white, size: 18),
                  SizedBox(width: 10),
                  Text('MASUK', style: TextStyle(
                    fontFamily: 'Orbitron', fontWeight: FontWeight.bold,
                    fontSize: 14, letterSpacing: 4, color: Colors.white)),
                ]),
          )),
      ]),
    );
  }
}
