import 'package:flutter/foundation.dart';
import 'models.dart';
import 'database.dart';
import 'constants.dart';

class AppProvider extends ChangeNotifier {
  List<Person> _persons = [];
  AppSettings _settings = AppSettings();
  String _searchQuery = '';
  String _sortBy = 'name';
  bool _sortAsc = true;

  List<Person> get allPersons => _persons;
  AppSettings get settings => _settings;
  String get currencySymbol =>
      kCurrencySymbols[_settings.currencyIndex.clamp(0, kCurrencySymbols.length - 1)];
  String get currencyName =>
      kCurrencies[_settings.currencyIndex.clamp(0, kCurrencies.length - 1)];
  bool get darkMode => _settings.darkMode;

  List<Person> get filteredPersons {
    var list = List<Person>.from(_persons);
    if (_searchQuery.isNotEmpty) {
      list = list
          .where((p) =>
              p.name.toLowerCase().contains(_searchQuery.toLowerCase()) ||
              (p.phone ?? '').contains(_searchQuery))
          .toList();
    }
    list.sort((a, b) {
      int cmp;
      switch (_sortBy) {
        case 'balance':
          cmp = getBalance(a.id).compareTo(getBalance(b.id));
          break;
        case 'date':
          cmp = a.createdAt.compareTo(b.createdAt);
          break;
        default:
          cmp = a.name.compareTo(b.name);
      }
      return _sortAsc ? cmp : -cmp;
    });
    return list;
  }

  double get totalCredit {
    double total = 0;
    for (final p in _persons) {
      final bal = getBalance(p.id);
      if (bal > 0) total += bal;
    }
    return total;
  }

  double get totalDebit {
    double total = 0;
    for (final p in _persons) {
      final bal = getBalance(p.id);
      if (bal < 0) total += bal.abs();
    }
    return total;
  }

  int get personCount => _persons.length;

  void load() {
    _persons = AppDatabase.getAllPersons();
    _settings = AppDatabase.getSettings();
    notifyListeners();
  }

  double getBalance(String personId) => AppDatabase.getBalanceForPerson(personId);

  List<Transaction> getTransactions(String personId) =>
      AppDatabase.getTransactionsForPerson(personId);

  void setSearch(String q) {
    _searchQuery = q;
    notifyListeners();
  }

  void setSort(String by, {bool? asc}) {
    _sortBy = by;
    if (asc != null) _sortAsc = asc;
    notifyListeners();
  }

  Future<void> addPerson(Person p) async {
    await AppDatabase.savePerson(p);
    load();
  }

  Future<void> updatePerson(Person p) async {
    await AppDatabase.savePerson(p);
    load();
  }

  Future<void> deletePerson(String id) async {
    await AppDatabase.deletePerson(id);
    load();
  }

  Future<void> addTransaction(Transaction t) async {
    await AppDatabase.saveTransaction(t);
    notifyListeners();
  }

  Future<void> deleteTransaction(String id) async {
    await AppDatabase.deleteTransaction(id);
    notifyListeners();
  }

  Future<void> updateSettings(AppSettings s) async {
    _settings = s;
    await AppDatabase.saveSettings(s);
    notifyListeners();
  }

  Future<String?> exportAll() => AppDatabase.exportAllCSV(currencySymbol);

  String exportPersonCSV(String personId, String personName) =>
      AppDatabase.exportCSV(personId, personName, currencySymbol);
}
