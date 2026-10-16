import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../core/providers.dart';
import '../core/database.dart';
import '../core/models.dart';

class StatisticsScreen extends StatelessWidget {
  const StatisticsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final prov = context.watch<AppProvider>();
    final sym = prov.currencySymbol;
    final persons = prov.allPersons;

    final creditPersons = persons.where((p) => prov.getBalance(p.id) > 0).toList()
      ..sort((a, b) => prov.getBalance(b.id).compareTo(prov.getBalance(a.id)));
    final debitPersons = persons.where((p) => prov.getBalance(p.id) < 0).toList()
      ..sort((a, b) => prov.getBalance(a.id).compareTo(prov.getBalance(b.id)));

    final allTx = AppDatabase.getAllTransactions();
    final txThisMonth = allTx.where((t) {
      final now = DateTime.now();
      return t.date.month == now.month && t.date.year == now.year;
    }).toList();
    final monthlyCredit = txThisMonth.where((t) => t.isCredit).fold(0.0, (s, t) => s + t.amount);
    final monthlyDebit = txThisMonth.where((t) => !t.isCredit).fold(0.0, (s, t) => s + t.amount);

    return Scaffold(
      appBar: AppBar(title: const Text('الإحصائيات')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _sectionTitle(context, 'هذا الشهر'),
          Row(
            children: [
              Expanded(child: _StatCard(label: 'إجمالي الإيداعات', value: '${monthlyCredit.toStringAsFixed(2)} $sym', color: Colors.green)),
              const SizedBox(width: 12),
              Expanded(child: _StatCard(label: 'إجمالي السحوبات', value: '${monthlyDebit.toStringAsFixed(2)} $sym', color: Colors.red)),
            ],
          ),
          const SizedBox(height: 8),
          _StatCard(label: 'عدد الحركات هذا الشهر', value: '${txThisMonth.length}', color: Colors.blue),
          const SizedBox(height: 20),
          _sectionTitle(context, 'الإجمالي'),
          Row(
            children: [
              Expanded(child: _StatCard(label: 'لي من الآخرين', value: '${prov.totalCredit.toStringAsFixed(2)} $sym', color: Colors.green)),
              const SizedBox(width: 12),
              Expanded(child: _StatCard(label: 'للآخرين مني', value: '${prov.totalDebit.toStringAsFixed(2)} $sym', color: Colors.red)),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(child: _StatCard(label: 'إجمالي الأشخاص', value: '${prov.personCount}', color: Colors.purple)),
              const SizedBox(width: 12),
              Expanded(child: _StatCard(label: 'إجمالي الحركات', value: '${allTx.length}', color: Colors.orange)),
            ],
          ),
          if (creditPersons.isNotEmpty) ...[
            const SizedBox(height: 20),
            _sectionTitle(context, 'أعلى 5 - لي عليهم'),
            ...creditPersons.take(5).map((p) => _PersonRankTile(
              name: p.name,
              balance: prov.getBalance(p.id),
              symbol: sym,
              positive: true,
            )),
          ],
          if (debitPersons.isNotEmpty) ...[
            const SizedBox(height: 20),
            _sectionTitle(context, 'أعلى 5 - عليّ لهم'),
            ...debitPersons.take(5).map((p) => _PersonRankTile(
              name: p.name,
              balance: prov.getBalance(p.id).abs(),
              symbol: sym,
              positive: false,
            )),
          ],
        ],
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(title, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
    );
  }
}

class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  final Color color;

  const _StatCard({required this.label, required this.value, required this.color});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontSize: 11, color: Colors.grey)),
            const SizedBox(height: 6),
            Text(value, style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: color)),
          ],
        ),
      ),
    );
  }
}

class _PersonRankTile extends StatelessWidget {
  final String name;
  final double balance;
  final String symbol;
  final bool positive;

  const _PersonRankTile({required this.name, required this.balance, required this.symbol, required this.positive});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(positive ? Icons.arrow_downward : Icons.arrow_upward, color: positive ? Colors.green : Colors.red),
      title: Text(name),
      trailing: Text('${balance.toStringAsFixed(2)} $symbol',
          style: TextStyle(fontWeight: FontWeight.bold, color: positive ? Colors.green : Colors.red)),
    );
  }
}
