import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'core/database.dart';
import 'core/providers.dart';
import 'core/theme.dart';
import 'screens/home_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppDatabase.init();
  runApp(
    ChangeNotifierProvider(
      create: (_) => AppProvider()..load(),
      child: const AccountsApp(),
    ),
  );
}

class AccountsApp extends StatelessWidget {
  const AccountsApp({super.key});

  @override
  Widget build(BuildContext context) {
    final prov = context.watch<AppProvider>();
    return MaterialApp(
      title: 'مدونة الحسابات',
      debugShowCheckedModeBanner: false,
      theme: buildTheme(dark: false),
      darkTheme: buildTheme(dark: true),
      themeMode: prov.darkMode ? ThemeMode.dark : ThemeMode.light,
      locale: const Locale('ar'),
      builder: (context, child) => Directionality(
        textDirection: TextDirection.rtl,
        child: child!,
      ),
      home: const HomeScreen(),
    );
  }
}
