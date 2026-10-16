import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../core/models.dart';
import '../core/providers.dart';

class AddTransactionScreen extends StatefulWidget {
  final Person person;
  const AddTransactionScreen({super.key, required this.person});

  @override
  State<AddTransactionScreen> createState() => _AddTransactionScreenState();
}

class _AddTransactionScreenState extends State<AddTransactionScreen> {
  final _form = GlobalKey<FormState>();
  final _amountCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  TransactionType _type = TransactionType.credit;
  bool _saving = false;

  @override
  void dispose() {
    _amountCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_form.currentState!.validate()) return;
    setState(() => _saving = true);
    final amount = double.parse(_amountCtrl.text.trim().replaceAll(',', '.'));
    final t = Transaction(
      personId: widget.person.id,
      amount: amount,
      type: _type,
      description: _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
    );
    await context.read<AppProvider>().addTransaction(t);
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final sym = context.read<AppProvider>().currencySymbol;

    return Scaffold(
      appBar: AppBar(title: Text('حركة جديدة - ${widget.person.name}')),
      body: Form(
        key: _form,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  children: [
                    Expanded(
                      child: RadioListTile<TransactionType>(
                        title: const Text('له عليّ\n(أعطيته)', textAlign: TextAlign.center, style: TextStyle(fontSize: 13)),
                        value: TransactionType.credit,
                        groupValue: _type,
                        activeColor: Colors.green,
                        onChanged: (v) => setState(() => _type = v!),
                      ),
                    ),
                    Expanded(
                      child: RadioListTile<TransactionType>(
                        title: const Text('عليه لي\n(أخذت منه)', textAlign: TextAlign.center, style: TextStyle(fontSize: 13)),
                        value: TransactionType.debit,
                        groupValue: _type,
                        activeColor: Colors.red,
                        onChanged: (v) => setState(() => _type = v!),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _amountCtrl,
              textDirection: TextDirection.ltr,
              decoration: InputDecoration(
                labelText: 'المبلغ *',
                prefixIcon: const Icon(Icons.attach_money),
                suffixText: sym,
              ),
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              validator: (v) {
                if (v == null || v.trim().isEmpty) return 'المبلغ مطلوب';
                final n = double.tryParse(v.trim().replaceAll(',', '.'));
                if (n == null || n <= 0) return 'أدخل مبلغاً صحيحاً';
                return null;
              },
              textInputAction: TextInputAction.next,
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _descCtrl,
              textDirection: TextDirection.rtl,
              decoration: const InputDecoration(labelText: 'الوصف (اختياري)', prefixIcon: Icon(Icons.description)),
              maxLines: 2,
              textInputAction: TextInputAction.done,
            ),
            const SizedBox(height: 32),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: _type == TransactionType.credit ? Colors.green.shade50 : Colors.red.shade50,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: _type == TransactionType.credit ? Colors.green.shade200 : Colors.red.shade200),
              ),
              child: Text(
                _type == TransactionType.credit
                    ? '${widget.person.name} أعطاك مبلغاً → يُضاف لرصيده لصالحك'
                    : '${widget.person.name} أخذ منك مبلغاً → يُضاف لرصيده ضدّك',
                textAlign: TextAlign.center,
                style: TextStyle(color: _type == TransactionType.credit ? Colors.green.shade700 : Colors.red.shade700, fontSize: 13),
              ),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              onPressed: _saving ? null : _save,
              icon: _saving
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Icon(Icons.check),
              label: const Text('تسجيل الحركة'),
            ),
          ],
        ),
      ),
    );
  }
}
