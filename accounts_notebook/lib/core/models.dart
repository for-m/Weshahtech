import 'package:uuid/uuid.dart';

const _uuid = Uuid();

class Person {
  final String id;
  String name;
  String? phone;
  String? notes;
  final DateTime createdAt;

  Person({
    String? id,
    required this.name,
    this.phone,
    this.notes,
    DateTime? createdAt,
  })  : id = id ?? _uuid.v4(),
        createdAt = createdAt ?? DateTime.now();

  Map<String, dynamic> toMap() => {
        'id': id,
        'name': name,
        'phone': phone,
        'notes': notes,
        'createdAt': createdAt.millisecondsSinceEpoch,
      };

  factory Person.fromMap(Map<dynamic, dynamic> m) => Person(
        id: m['id'] as String,
        name: m['name'] as String,
        phone: m['phone'] as String?,
        notes: m['notes'] as String?,
        createdAt: DateTime.fromMillisecondsSinceEpoch(m['createdAt'] as int),
      );
}

enum TransactionType { credit, debit }

class Transaction {
  final String id;
  final String personId;
  double amount;
  TransactionType type;
  String? description;
  final DateTime date;

  Transaction({
    String? id,
    required this.personId,
    required this.amount,
    required this.type,
    this.description,
    DateTime? date,
  })  : id = id ?? _uuid.v4(),
        date = date ?? DateTime.now();

  bool get isCredit => type == TransactionType.credit;

  Map<String, dynamic> toMap() => {
        'id': id,
        'personId': personId,
        'amount': amount,
        'type': type.index,
        'description': description,
        'date': date.millisecondsSinceEpoch,
      };

  factory Transaction.fromMap(Map<dynamic, dynamic> m) => Transaction(
        id: m['id'] as String,
        personId: m['personId'] as String,
        amount: (m['amount'] as num).toDouble(),
        type: TransactionType.values[m['type'] as int],
        description: m['description'] as String?,
        date: DateTime.fromMillisecondsSinceEpoch(m['date'] as int),
      );
}

class AppSettings {
  int currencyIndex;
  bool darkMode;
  String? pinCode;
  String? backupPath;

  AppSettings({
    this.currencyIndex = 0,
    this.darkMode = false,
    this.pinCode,
    this.backupPath,
  });

  Map<String, dynamic> toMap() => {
        'currencyIndex': currencyIndex,
        'darkMode': darkMode,
        'pinCode': pinCode,
        'backupPath': backupPath,
      };

  factory AppSettings.fromMap(Map<dynamic, dynamic> m) => AppSettings(
        currencyIndex: m['currencyIndex'] as int? ?? 0,
        darkMode: m['darkMode'] as bool? ?? false,
        pinCode: m['pinCode'] as String?,
        backupPath: m['backupPath'] as String?,
      );
}
