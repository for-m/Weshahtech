import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../core/providers.dart';
import '../core/models.dart';
import '../core/constants.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final prov = context.watch<AppProvider>();
    final s = prov.settings;

    return Scaffold(
      appBar: AppBar(title: const Text('الإعدادات')),
      body: ListView(
        children: [
          const _SectionHeader('العرض'),
          SwitchListTile(
            title: const Text('الوضع الداكن'),
            subtitle: const Text('تغيير مظهر التطبيق'),
            secondary: const Icon(Icons.dark_mode_outlined),
            value: s.darkMode,
            onChanged: (v) {
              final ns = AppSettings(
                currencyIndex: s.currencyIndex,
                darkMode: v,
                pinCode: s.pinCode,
              );
              prov.updateSettings(ns);
            },
          ),
          const Divider(height: 1),
          ListTile(
            leading: const Icon(Icons.currency_exchange),
            title: const Text('العملة'),
            subtitle: Text(prov.currencyName),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _showCurrencyPicker(context, prov),
          ),
          const _SectionHeader('الأمان'),
          ListTile(
            leading: const Icon(Icons.lock_outline),
            title: const Text('رمز الحماية'),
            subtitle: Text(s.pinCode != null ? 'مُفعَّل' : 'غير مُفعَّل'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _showPinDialog(context, prov),
          ),
          const _SectionHeader('البيانات'),
          ListTile(
            leading: const Icon(Icons.download_outlined),
            title: const Text('تصدير كل البيانات (CSV)'),
            subtitle: const Text('تصدير قائمة الأشخاص والأرصدة'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () async {
              final path = await prov.exportAll();
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(path != null ? 'تم التصدير إلى: $path' : 'فشل التصدير'),
                    backgroundColor: path != null ? Colors.green : Colors.red,
                  ),
                );
              }
            },
          ),
          const _SectionHeader('حول التطبيق'),
          const ListTile(
            leading: Icon(Icons.info_outline),
            title: Text('مدونة الحسابات'),
            subtitle: Text('الإصدار 1.0.0 - تطوير Weshah Tech'),
          ),
        ],
      ),
    );
  }

  void _showCurrencyPicker(BuildContext context, AppProvider prov) {
    showModalBottomSheet(
      context: context,
      builder: (_) => ListView.builder(
        shrinkWrap: true,
        itemCount: kCurrencies.length,
        itemBuilder: (_, i) => ListTile(
          title: Text(kCurrencies[i]),
          leading: Text(kCurrencySymbols[i], style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
          trailing: prov.settings.currencyIndex == i ? const Icon(Icons.check, color: Colors.green) : null,
          onTap: () {
            final ns = AppSettings(
              currencyIndex: i,
              darkMode: prov.settings.darkMode,
              pinCode: prov.settings.pinCode,
            );
            prov.updateSettings(ns);
            Navigator.pop(context);
          },
        ),
      ),
    );
  }

  void _showPinDialog(BuildContext context, AppProvider prov) {
    final ctrl = TextEditingController();
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('رمز الحماية'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (prov.settings.pinCode != null)
              TextButton.icon(
                onPressed: () {
                  final ns = AppSettings(
                    currencyIndex: prov.settings.currencyIndex,
                    darkMode: prov.settings.darkMode,
                    pinCode: null,
                  );
                  prov.updateSettings(ns);
                  Navigator.pop(context);
                },
                icon: const Icon(Icons.lock_open, color: Colors.red),
                label: const Text('إلغاء رمز الحماية', style: TextStyle(color: Colors.red)),
              ),
            TextField(
              controller: ctrl,
              decoration: const InputDecoration(labelText: 'رمز جديد (4 أرقام)'),
              keyboardType: TextInputType.number,
              maxLength: 4,
              obscureText: true,
              textDirection: TextDirection.ltr,
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('إلغاء')),
          ElevatedButton(
            onPressed: () {
              if (ctrl.text.length == 4) {
                final ns = AppSettings(
                  currencyIndex: prov.settings.currencyIndex,
                  darkMode: prov.settings.darkMode,
                  pinCode: ctrl.text,
                );
                prov.updateSettings(ns);
                Navigator.pop(context);
              }
            },
            child: const Text('حفظ'),
          ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  const _SectionHeader(this.title);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: Text(title, style: TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.bold, fontSize: 13)),
    );
  }
}
