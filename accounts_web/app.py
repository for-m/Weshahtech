import sqlite3, uuid, os, io, csv
from datetime import datetime
from flask import Flask, request, jsonify, send_from_directory

app = Flask(__name__, static_folder='.')
DB = os.path.join(os.path.dirname(__file__), 'accounts.db')

# ── Database ──────────────────────────────────────────────────────────────────

def get_db():
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA journal_mode=WAL')
    conn.execute('PRAGMA foreign_keys=ON')
    return conn

def init_db():
    with get_db() as c:
        c.execute('''CREATE TABLE IF NOT EXISTS persons (
            id TEXT PRIMARY KEY, name TEXT NOT NULL,
            phone TEXT, notes TEXT,
            created_at INTEGER DEFAULT (strftime('%s','now')*1000))''')
        c.execute('''CREATE TABLE IF NOT EXISTS transactions (
            id TEXT PRIMARY KEY, person_id TEXT NOT NULL,
            amount REAL NOT NULL, type INTEGER NOT NULL,
            description TEXT,
            date INTEGER DEFAULT (strftime('%s','now')*1000),
            FOREIGN KEY(person_id) REFERENCES persons(id))''')
        c.execute('''CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY, value TEXT)''')
        c.execute("INSERT OR IGNORE INTO settings VALUES('currency_index','0')")
        c.execute("INSERT OR IGNORE INTO settings VALUES('dark_mode','false')")
        c.commit()

def balance(c, person_id):
    row = c.execute(
        'SELECT COALESCE(SUM(CASE WHEN type=0 THEN amount ELSE -amount END),0)'
        ' FROM transactions WHERE person_id=?', (person_id,)).fetchone()
    return row[0]

# ── Persons ───────────────────────────────────────────────────────────────────

@app.get('/api/persons')
def list_persons():
    sort = request.args.get('sort', 'name')
    with get_db() as c:
        rows = c.execute('SELECT * FROM persons').fetchall()
        persons = []
        for r in rows:
            bal = balance(c, r['id'])
            persons.append({**dict(r), 'balance': bal})
        if sort == 'balance':
            persons.sort(key=lambda x: x['balance'])
        elif sort == 'date':
            persons.sort(key=lambda x: x['created_at'])
        else:
            persons.sort(key=lambda x: x['name'])
        return jsonify(persons)

@app.post('/api/persons')
def add_person():
    d = request.json
    pid = str(uuid.uuid4())
    with get_db() as c:
        c.execute('INSERT INTO persons(id,name,phone,notes) VALUES(?,?,?,?)',
                  (pid, d['name'], d.get('phone'), d.get('notes')))
        c.commit()
    return jsonify({'id': pid}), 201

@app.put('/api/persons/<pid>')
def update_person(pid):
    d = request.json
    with get_db() as c:
        c.execute('UPDATE persons SET name=?,phone=?,notes=? WHERE id=?',
                  (d['name'], d.get('phone'), d.get('notes'), pid))
        c.commit()
    return jsonify({'ok': True})

@app.delete('/api/persons/<pid>')
def delete_person(pid):
    with get_db() as c:
        c.execute('DELETE FROM transactions WHERE person_id=?', (pid,))
        c.execute('DELETE FROM persons WHERE id=?', (pid,))
        c.commit()
    return jsonify({'ok': True})

# ── Transactions ──────────────────────────────────────────────────────────────

@app.get('/api/persons/<pid>/transactions')
def list_transactions(pid):
    with get_db() as c:
        rows = c.execute(
            'SELECT * FROM transactions WHERE person_id=? ORDER BY date DESC', (pid,))
        return jsonify([dict(r) for r in rows])

@app.post('/api/transactions')
def add_transaction():
    d = request.json
    tid = str(uuid.uuid4())
    with get_db() as c:
        c.execute('INSERT INTO transactions(id,person_id,amount,type,description) VALUES(?,?,?,?,?)',
                  (tid, d['person_id'], d['amount'], d['type'], d.get('description')))
        c.commit()
    return jsonify({'id': tid}), 201

@app.delete('/api/transactions/<tid>')
def delete_transaction(tid):
    with get_db() as c:
        c.execute('DELETE FROM transactions WHERE id=?', (tid,))
        c.commit()
    return jsonify({'ok': True})

# ── Stats ─────────────────────────────────────────────────────────────────────

@app.get('/api/stats')
def stats():
    with get_db() as c:
        persons = c.execute('SELECT id FROM persons').fetchall()
        total_credit = total_debit = 0
        for p in persons:
            bal = balance(c, p['id'])
            if bal > 0: total_credit += bal
            elif bal < 0: total_debit += abs(bal)

        now = datetime.now()
        month_start = int(datetime(now.year, now.month, 1).timestamp() * 1000)
        month_rows = c.execute(
            'SELECT type, amount FROM transactions WHERE date>=?', (month_start,)).fetchall()
        month_credit = sum(r['amount'] for r in month_rows if r['type'] == 0)
        month_debit = sum(r['amount'] for r in month_rows if r['type'] == 1)

        total_tx = c.execute('SELECT COUNT(*) FROM transactions').fetchone()[0]
        total_persons = len(persons)
        return jsonify({
            'total_credit': total_credit, 'total_debit': total_debit,
            'total_persons': total_persons, 'total_tx': total_tx,
            'month_credit': month_credit, 'month_debit': month_debit,
            'month_tx': len(month_rows),
        })

@app.get('/api/recent')
def recent():
    with get_db() as c:
        rows = c.execute('''
            SELECT t.*, p.name as person_name
            FROM transactions t JOIN persons p ON t.person_id=p.id
            ORDER BY t.date DESC LIMIT 15''').fetchall()
        return jsonify([dict(r) for r in rows])

# ── Settings ──────────────────────────────────────────────────────────────────

@app.get('/api/settings')
def get_settings():
    with get_db() as c:
        rows = c.execute('SELECT key,value FROM settings').fetchall()
        return jsonify({r['key']: r['value'] for r in rows})

@app.put('/api/settings')
def put_settings():
    d = request.json
    with get_db() as c:
        for k, v in d.items():
            c.execute('INSERT OR REPLACE INTO settings VALUES(?,?)', (k, str(v)))
        c.commit()
    return jsonify({'ok': True})

# ── Export ────────────────────────────────────────────────────────────────────

@app.get('/api/export/csv')
def export_csv():
    with get_db() as c:
        sym_idx = int(c.execute("SELECT value FROM settings WHERE key='currency_index'").fetchone()['value'])
        symbols = ['₪','$','JD','ج.م','ر.س','د.إ','€']
        sym = symbols[sym_idx] if sym_idx < len(symbols) else '₪'
        persons = c.execute('SELECT * FROM persons ORDER BY name').fetchall()
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(['الاسم','الهاتف',f'الرصيد ({sym})','ملاحظات'])
        for p in persons:
            bal = balance(c, p['id'])
            w.writerow([p['name'], p['phone'] or '', f'{bal:.2f}', p['notes'] or ''])
    from flask import Response
    return Response(buf.getvalue(), mimetype='text/csv; charset=utf-8',
                    headers={'Content-Disposition': 'attachment; filename=accounts.csv'})

@app.get('/api/export/person/<pid>')
def export_person_csv(pid):
    with get_db() as c:
        sym_idx = int(c.execute("SELECT value FROM settings WHERE key='currency_index'").fetchone()['value'])
        symbols = ['₪','$','JD','ج.م','ر.س','د.إ','€']
        sym = symbols[sym_idx] if sym_idx < len(symbols) else '₪'
        p = c.execute('SELECT * FROM persons WHERE id=?', (pid,)).fetchone()
        txs = c.execute('SELECT * FROM transactions WHERE person_id=? ORDER BY date', (pid,)).fetchall()
        buf = io.StringIO()
        buf.write(f'﻿')
        w = csv.writer(buf)
        w.writerow(['التاريخ','النوع',f'المبلغ ({sym})','الوصف'])
        running = 0
        for t in txs:
            if t['type'] == 0: running += t['amount']
            else: running -= t['amount']
            dt = datetime.fromtimestamp(t['date']/1000).strftime('%Y-%m-%d')
            w.writerow([dt, 'له' if t['type']==0 else 'عليه', f"{t['amount']:.2f}", t['description'] or ''])
        w.writerow([])
        w.writerow(['', '', f'الرصيد: {running:.2f} {sym}', ''])
    from flask import Response
    fname = f"{p['name']}_statement.csv"
    return Response(buf.getvalue(), mimetype='text/csv; charset=utf-8',
                    headers={'Content-Disposition': f'attachment; filename="{fname}"'})

# ── Serve index.html ──────────────────────────────────────────────────────────

@app.get('/')
@app.get('/<path:path>')
def serve(path='index.html'):
    return send_from_directory('.', 'index.html')

# ─────────────────────────────────────────────────────────────────────────────

if __name__ == '__main__':
    init_db()
    host = '0.0.0.0'
    port = int(os.environ.get('PORT', 5050))
    print(f'\n  مدونة الحسابات تعمل على:')
    print(f'  ▶ http://localhost:{port}')
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        print(f'  ▶ http://{ip}:{port}  ← شارك هذا الرابط على الشبكة')
    except: pass
    print()
    app.run(host=host, port=port, debug=False)
