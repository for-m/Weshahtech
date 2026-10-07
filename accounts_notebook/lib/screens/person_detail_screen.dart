import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../core/models.dart';
import '../core/providers.dart';
import 'add_person_screen.dart';
import 'add_transaction_screen.dart';

class PersonDetailScreen extends StatelessWidget {
  final Person person;
  const PersonDetailScreen({super.key, required this.person});

  @override
  Widget build(BuildContext context) {
    final prov = context.watch<AppProvider>();
    final balance = prov.getBalance(person.id);
    final transactions = prov.getTransactions(person.id);
    final sym = prov.currencySymbol;
    final isPositive = balance >= 0;

    return Scaffold(
      appBar: AppBar(
        title: Text(person.name),
        actions: [
          IconButton(
            icon: const Icon(Icons.edit),
            tooltip: 'تعديل',
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => AddPersonScreen(person: person)),
            ),
          ),
          PopupMenuButton(
            itemBuilder: (_) => [
              const PopupMenuItem(value: 'export', child: Row(children: [Icon(Icons.download, size: 18), SizedBox(width: 8), Text('تصدير CSV')])),
              const PopupMenuItem(value: 'delete', child: Row(children: [Icon(Icons.delete, size: 18, color: Colors.red), SizedBox(width: 8), Text('حذف', style: TextStyle(color: Colors.red))])),
            ],
            onSelected: (v) async {
              if (v == 'export') {
                final csv = prov.exportPersonCSV(person.id, person.name);
                _showExportDialog(context, csv);
              } else if (v == 'delete') {
                _confirmDelete(context, prov);
              }
            },
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            width: double.infinity,
            color: isPositive ? Colors.green.shade50 : Colors.red.shade50,
            padding: const EdgeInsets.all(20),
            child: Column(
              children: [
                Text(
                  '${balance.abs().toStringAsFixed(2)} $sym',
                  style: TextStyle(
                    fontSize: 32,
                    fontWeight: FontWeight.bold,
                    color: isPositive ? Colors.green.shade700 : Colors.red.shade700,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  balance == 0 ? 'الحساب مسوّى' : (isPositive ? 'له عليك' : 'عليه لك'),
                  style: TextStyle(fontSize: 14, color: isPositive ? Colors.green : Colors.red),
                ),
                if (person.phone != null) ...[  
                  const SizedBox(height: 8),
                  Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    const Icon(Icons.phone, size: 14, color: Colors.grey),
                    const SizedBox(width: 4),
                    Text(person.phone!, style: const TextStyle(color: Colors.grey)),
                  ]),
                ],
              ],
            ),
          ),
          Expanded(
            child: transactions.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(Icons.receipt_long_outlined, size: 60, color: Colors.grey.shade400),
                        const SizedBox(height: 12),
                        const Text('لا توجد حركات', style: TextStyle(color: Colors.grey)),
                      ],
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.all(8),
                    itemCount: transactions.length,
                    itemBuilder: (_, i) {
                      final t = transactions[i];
                      return _TransactionTile(
                        transaction: t,
                        symbol: sym,
                        onDelete: () async {
                          final ok = await _confirmTxDelete(context);
                          if (ok) await prov.deleteTransaction(t.id);
                        },
                      );
                    },
                  ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => Navigator.push(
          context,
          MaterialPageRoute(builder: (_) => AddTransactionScreen(person: person)),
        ),
        icon: const Icon(Icons.add),
        label: const Text('إضافة حركة'),
      ),
    );
  }

  void _showExportDialog(BuildContext context, String csv) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('بيانات التصدير'),
        content: SingleChildScrollView(child: SelectableText(csv, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('إغلاق')),
        ],
      ),
    );
  }

  void _confirmDelete(BuildContext context, AppProvider prov) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('حذف الشخص'),
        content: Text('هل تريد حذف "${person.name}" وجميع حركاته؟ لا يمكن التراجع.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('إلغاء')),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              Navigator.pop(context);
              await prov.deletePerson(person.id);
              if (context.mounted) Navigator.pop(context);
            },
            child: const Text('حذف', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
  }

  Future<bool> _confirmTxDelete(BuildContext context) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('حذف الحركة'),
        content: const Text('هل تريد حذف هذه الحركة؟'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('إلغاء')),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('حذف', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
    );
    return result ?? false;
  }
}

class _TransactionTile extends StatelessWidget {
  final Transaction transaction;
  final String symbol;
  final VoidCallback onDelete;

  const _TransactionTile({required this.transaction, required this.symbol, required this.onDelete});

  @override
  Widget build(BuildContext context) {
    final t = transaction;
    final d = t.date;
    final dateStr = '${d.day}/${d.month}/${d.year}';

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: t.isCredit ? Colors.green.shade100 : Colors.red.shade100,
          child: Icon(t.isCredit ? Icons.arrow_back : Icons.arrow_forward,
              color: t.isCredit ? Colors.green : Colors.red, size: 20),
        ),
        title: Text(
          t.description ?? (t.isCredit ? 'له عليّ' : 'عليه لي'),
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
        subtitle: Text(dateStr, style: const TextStyle(fontSize: 11, color: Colors.grey)),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '${t.isCredit ? "+" : "-"}${t.amount.toStringAsFixed(2)} $symbol',
              style: TextStyle(fontWeight: FontWeight.bold, color: t.isCredit ? Colors.green : Colors.red, fontSize: 14),
            ),
            IconButton(icon: const Icon(Icons.delete_outline, size: 18, color: Colors.grey), onPressed: onDelete),
          ],
        ),
      ),
    );
  }
}
