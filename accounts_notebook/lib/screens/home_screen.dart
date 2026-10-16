import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../core/providers.dart';
import '../core/database.dart';
import 'accounts_screen.dart';
import 'statistics_screen.dart';
import 'settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) {
    final screens = [
      const _DashboardTab(),
      const AccountsScreen(),
      const StatisticsScreen(),
      const SettingsScreen(),
    ];
    return Scaffold(
      body: screens[_tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.dashboard_outlined), selectedIcon: Icon(Icons.dashboard), label: 'الرئيسية'),
          NavigationDestination(icon: Icon(Icons.people_outline), selectedIcon: Icon(Icons.people), label: 'الحسابات'),
          NavigationDestination(icon: Icon(Icons.bar_chart_outlined), selectedIcon: Icon(Icons.bar_chart), label: 'الإحصائيات'),
          NavigationDestination(icon: Icon(Icons.settings_outlined), selectedIcon: Icon(Icons.settings), label: 'الإعدادات'),
        ],
      ),
    );
  }
}

class _DashboardTab extends StatelessWidget {
  const _DashboardTab();

  @override
  Widget build(BuildContext context) {
    final prov = context.watch<AppProvider>();
    final cs = Theme.of(context).colorScheme;
    final sym = prov.currencySymbol;

    return Scaffold(
      appBar: AppBar(
        title: const Text('مدونة الحسابات', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: prov.load,
            tooltip: 'تحديث',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async => prov.load(),
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Row(
              children: [
                Expanded(child: _SummaryCard(title: 'إجمالي لي', amount: prov.totalCredit, symbol: sym, color: Colors.green, icon: Icons.arrow_downward)),
                const SizedBox(width: 12),
                Expanded(child: _SummaryCard(title: 'إجمالي علي', amount: prov.totalDebit, symbol: sym, color: Colors.red, icon: Icons.arrow_upward)),
              ],
            ),
            const SizedBox(height: 12),
            _SummaryCard(title: 'عدد الأشخاص', amount: prov.personCount.toDouble(), symbol: '', color: cs.primary, icon: Icons.people, isCount: true),
            const SizedBox(height: 24),
            Text('آخر الحركات', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            _RecentTransactions(prov: prov, sym: sym),
          ],
        ),
      ),
    );
  }
}

class _RecentTransactions extends StatelessWidget {
  final AppProvider prov;
  final String sym;
  const _RecentTransactions({required this.prov, required this.sym});

  @override
  Widget build(BuildContext context) {
    final allTx = AppDatabase.getAllTransactions().take(10).toList();
    if (allTx.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: Text('لا توجد حركات حتى الآن\nأضف أشخاصاً وابدأ التسجيل',
              textAlign: TextAlign.center, style: TextStyle(color: Colors.grey)),
        ),
      );
    }
    final personMap = {for (final p in prov.allPersons) p.id: p.name};
    return Column(
      children: allTx.map((t) {
        final name = personMap[t.personId] ?? '...';
        return Card(
          margin: const EdgeInsets.symmetric(vertical: 4),
          child: ListTile(
            leading: CircleAvatar(
              backgroundColor: t.isCredit ? Colors.green.shade100 : Colors.red.shade100,
              child: Icon(t.isCredit ? Icons.add : Icons.remove, color: t.isCredit ? Colors.green : Colors.red, size: 20),
            ),
            title: Text(name),
            subtitle: Text(t.description ?? (t.isCredit ? 'له عليّ' : 'عليه لي'), style: const TextStyle(fontSize: 12)),
            trailing: Text(
              '${t.isCredit ? "+" : "-"}${t.amount.toStringAsFixed(2)} $sym',
              style: TextStyle(fontWeight: FontWeight.bold, color: t.isCredit ? Colors.green : Colors.red),
            ),
          ),
        );
      }).toList(),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  final String title;
  final double amount;
  final String symbol;
  final Color color;
  final IconData icon;
  final bool isCount;

  const _SummaryCard({
    required this.title,
    required this.amount,
    required this.symbol,
    required this.color,
    required this.icon,
    this.isCount = false,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: color.withOpacity(0.15),
              child: Icon(icon, color: color),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontSize: 12, color: Colors.grey)),
                  const SizedBox(height: 4),
                  Text(
                    isCount ? amount.toInt().toString() : '${amount.toStringAsFixed(2)} $symbol',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: color),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
