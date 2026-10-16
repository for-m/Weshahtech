import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../core/providers.dart';
import '../core/models.dart';
import 'add_person_screen.dart';
import 'person_detail_screen.dart';

class AccountsScreen extends StatefulWidget {
  const AccountsScreen({super.key});

  @override
  State<AccountsScreen> createState() => _AccountsScreenState();
}

class _AccountsScreenState extends State<AccountsScreen> {
  final _searchCtrl = TextEditingController();
  String _sortBy = 'name';

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final prov = context.watch<AppProvider>();
    final persons = prov.filteredPersons;
    final sym = prov.currencySymbol;

    return Scaffold(
      appBar: AppBar(
        title: const Text('الحسابات'),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.sort),
            tooltip: 'ترتيب',
            onSelected: (v) {
              setState(() => _sortBy = v);
              prov.setSort(v);
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'name', child: Text('الاسم')),
              PopupMenuItem(value: 'balance', child: Text('الرصيد')),
              PopupMenuItem(value: 'date', child: Text('التاريخ')),
            ],
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              controller: _searchCtrl,
              textDirection: TextDirection.rtl,
              decoration: InputDecoration(
                hintText: 'بحث باسم أو رقم هاتف...',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _searchCtrl.text.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _searchCtrl.clear();
                          prov.setSearch('');
                        },
                      )
                    : null,
                filled: true,
                fillColor: Theme.of(context).colorScheme.surface,
              ),
              onChanged: prov.setSearch,
            ),
          ),
        ),
      ),
      body: persons.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.people_outline, size: 80, color: Colors.grey.shade400),
                  const SizedBox(height: 16),
                  Text(_searchCtrl.text.isEmpty ? 'لا توجد حسابات\nاضغط + لإضافة شخص' : 'لا توجد نتائج',
                      textAlign: TextAlign.center, style: const TextStyle(color: Colors.grey)),
                ],
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.all(8),
              itemCount: persons.length,
              itemBuilder: (_, i) {
                final p = persons[i];
                final balance = prov.getBalance(p.id);
                return _PersonTile(person: p, balance: balance, symbol: sym);
              },
            ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const AddPersonScreen())),
        icon: const Icon(Icons.person_add),
        label: const Text('إضافة شخص'),
      ),
    );
  }
}

class _PersonTile extends StatelessWidget {
  final Person person;
  final double balance;
  final String symbol;

  const _PersonTile({required this.person, required this.balance, required this.symbol});

  @override
  Widget build(BuildContext context) {
    final isPositive = balance >= 0;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: isPositive ? Colors.green.shade100 : Colors.red.shade100,
          child: Text(
            person.name.isNotEmpty ? person.name[0] : '?',
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: isPositive ? Colors.green.shade700 : Colors.red.shade700,
            ),
          ),
        ),
        title: Text(person.name, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: person.phone != null ? Text(person.phone!) : null,
        trailing: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              '${balance.abs().toStringAsFixed(2)} $symbol',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: isPositive ? Colors.green : Colors.red,
                fontSize: 14,
              ),
            ),
            Text(
              balance == 0 ? 'مسوّى' : (isPositive ? 'له عليّ' : 'عليه لي'),
              style: TextStyle(fontSize: 11, color: isPositive ? Colors.green : Colors.red),
            ),
          ],
        ),
        onTap: () => Navigator.push(
          context,
          MaterialPageRoute(builder: (_) => PersonDetailScreen(person: person)),
        ),
      ),
    );
  }
}
