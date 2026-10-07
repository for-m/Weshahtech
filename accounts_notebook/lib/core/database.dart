import 'dart:io';
import 'package:hive/hive.dart';
import 'constants.dart';
import 'models.dart';

class AppDatabase {
  static late Box _personsBox;
  static late Box _transactionsBox;
  static late Box _settingsBox;

  static Future<void> init() async {
    String hivePath;
    if (Platform.isAndroid) {
      hivePath = '/data/data/$kAppPackage/files/hive';
    } else {
      hivePath = '${Directory.systemTemp.path}/accounts_notebook_hive';
    }
    final dir = Directory(hivePath);
    if (!dir.existsSync()) dir.createSync(recursive: true);
    Hive.init(hivePath);

    _personsBox = await Hive.openBox(kPersonsBox);
    _transactionsBox = await Hive.openBox(kTransactionsBox);
    _settingsBox = await Hive.openBox(kSettingsBox);
  }

  static List<Person> getAllPersons() {
    return _personsBox.values
        .map((v) => Person.fromMap(v as Map))
        .toList()
      ..sort((a, b) => a.name.compareTo(b.name));
  }

  static Future<void> savePerson(Person p) async {
    await _personsBox.put(p.id, p.toMap());
  }

  static Future<void> deletePerson(String id) async {
    await _personsBox.delete(id);
    final txKeys = _transactionsBox.keys
        .where((k) {
          final v = _transactionsBox.get(k);
          return v != null && (v as Map)['personId'] == id;
        })
        .toList();
    for (final k in txKeys) {
      await _transactionsBox.delete(k);
    }
  }

  static List<Transaction> getTransactionsForPerson(String personId) {
    return _transactionsBox.values
        .where((v) => (v as Map)['personId'] == personId)
        .map((v) => Transaction.fromMap(v as Map))
        .toList()
      ..sort((a, b) => b.date.compareTo(a.date));
  }

  static List<Transaction> getAllTransactions() {
    return _transactionsBox.values
        .map((v) => Transaction.fromMap(v as Map))
        .toList()
      ..sort((a, b) => b.date.compareTo(a.date));
  }

  static Future<void> saveTransaction(Transaction t) async {
    await _transactionsBox.put(t.id, t.toMap());
  }

  static Future<void> deleteTransaction(String id) async {
    await _transactionsBox.delete(id);
  }

  static double getBalanceForPerson(String personId) {
    double balance = 0;
    for (final v in _transactionsBox.values) {
      final m = v as Map;
      if (m['personId'] == personId) {
        final amount = (m['amount'] as num).toDouble();
        if (m['type'] == TransactionType.credit.index) {
          balance += amount;
        } else {
          balance -= amount;
        }
      }
    }
    return balance;
  }

  static AppSettings getSettings() {
    final m = _settingsBox.get('settings');
    if (m == null) return AppSettings();
    return AppSettings.fromMap(m as Map);
  }

  static Future<void> saveSettings(AppSettings s) async {
    await _settingsBox.put('settings', s.toMap());
  }

  static String exportCSV(String personId, String personName, String symbol) {
    final txs = getTransactionsForPerson(personId);
    final sb = StringBuffer();
    sb.writeln('التاريخ,النوع,المبلغ,الوصف');
    double running = 0;
    for (final t in txs.reversed.toList()) {
      if (t.isCredit) {
        running += t.amount;
      } else {
        running -= t.amount;
      }
      sb.writeln(
          '${t.date.toIso8601String()},${t.isCredit ? "له" : "عليه"},${t.amount.toStringAsFixed(2)},${t.description ?? ""}');
    }
    sb.writeln(',,الرصيد: ${running.abs().toStringAsFixed(2)} $symbol,');
    return sb.toString();
  }

  static Future<String?> exportAllCSV(String symbol) async {
    try {
      final persons = getAllPersons();
      final sb = StringBuffer();
      sb.writeln('الاسم,الهاتف,الرصيد,ملاحظات');
      for (final p in persons) {
        final bal = getBalanceForPerson(p.id);
        sb.writeln('"${p.name}","${p.phone ?? ""}",${bal.toStringAsFixed(2)} $symbol,"${p.notes ?? ""}"');
      }
      String outPath;
      if (Platform.isAndroid) {
        outPath = '/data/data/$kAppPackage/files/export_${DateTime.now().millisecondsSinceEpoch}.csv';
      } else {
        outPath = '${Directory.systemTemp.path}/accounts_export.csv';
      }
      await File(outPath).writeAsString(sb.toString());
      return outPath;
    } catch (e) {
      return null;
    }
  }

  static Future<bool> backupData() async {
    try {
      await _personsBox.flush();
      await _transactionsBox.flush();
      await _settingsBox.flush();
      return true;
    } catch (e) {
      return false;
    }
  }
}
