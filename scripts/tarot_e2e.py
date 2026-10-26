"""Synthetic acceptance against real Java, PostgreSQL, Python RPC and a local model stub.

Requires MIRROR_TAROT_TEST_DSN (psycopg), MIRROR_TAROT_TEST_OUTPUT (outside git).
Never point this script at a production service/database. Model quality is NOT evaluated.
"""
import concurrent.futures
import json
import os
from pathlib import Path
import time
import urllib.request
import urllib.error
import uuid
import psycopg

BASE = os.getenv('MIRROR_TAROT_TEST_API', 'http://127.0.0.1:19050/api')
assert BASE.startswith('http://127.0.0.1:'), 'Local disposable service required'
OUTPUT = Path(os.environ['MIRROR_TAROT_TEST_OUTPUT'])
OUTPUT.mkdir(parents=True, exist_ok=True)

def request(path, data=None, token=None, method=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, data=json.dumps(data).encode() if data is not None else None,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        result = json.load(error)
    assert result['code'] == expected, (path, result)
    return result.get('data')

def account():
    payload = {'username': 'tarot_' + uuid.uuid4().hex[:12], 'password': 'SyntheticTarot789!'}
    request('/auth/register', payload)
    return request('/auth/login', payload)

def finished(analysis, token):
    deadline = time.monotonic() + 20
    while analysis['status'] in ('pending', 'running') and time.monotonic() < deadline:
        time.sleep(.3)
        analysis = request('/tarot/analyses/' + analysis['analysisId'], token=token)
    assert analysis['status'] == 'completed', analysis
    return analysis

with psycopg.connect(os.environ['MIRROR_TAROT_TEST_DSN'], autocommit=True) as db:
    assert db.execute('SELECT current_database()').fetchone()[0].startswith('codex_assistant_growth_test_')
    auth, other = account(), account()
    token, user = auth['token'], auth['user']['id']
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        draws = list(pool.map(lambda _: request('/tarot/daily/draw', {'clientRequestId': str(uuid.uuid4())}, token), range(12)))
    assert len({draw['draw']['id'] for draw in draws}) == 1
    assert request('/tarot/collection', token=token)['entries'][0]['dailyDrawCount'] == 1
    reading = request('/tarot/readings', {'clientRequestId': str(uuid.uuid4())}, token)
    options = {'diaryEnabled': False, 'diaryDays': 7, 'todosEnabled': False, 'mirrorEnabled': False}
    # Missing model is explicit, while card and collection still work.
    request('/tarot/readings/' + reading['readingId'] + '/analyses',
            {'clientRequestId': str(uuid.uuid4()), 'question': '如何开始画画？', 'options': options}, token, expected=400)
    request('/tarot/readings/' + reading['readingId'], token=other['token'], expected=404)
    request('/settings', {'aiProtocol': 'openai', 'aiProvider': 'openai', 'aiApiKey': 'synthetic-only',
                         'aiBaseUrl': 'http://127.0.0.1:18089/v1', 'aiModel': 'tarot-stub'}, token, 'PUT')
    endpoint = '/tarot/readings/' + reading['readingId'] + '/analyses'
    payload = {'clientRequestId': str(uuid.uuid4()), 'question': '如何开始画画？', 'options': options}
    analysis = finished(request(endpoint, payload, token), token)
    assert analysis['analysisId'] == request(endpoint, payload, token)['analysisId']
    assert not analysis['sources']
    record = db.execute("INSERT INTO records(user_id,content,source,status,user_reviewed,ai_processing_authorized,memory_state) VALUES(%s,'测试日记：我在练习画画，希望坚持。','user','done',true,true,'READY') RETURNING id", (user,)).fetchone()[0]
    db.execute("INSERT INTO chunks(record_id,user_id,content,segment,metadata,ai_memory_approved,embedding,embedding_status) VALUES(%s,%s,'画画练习','测试日记：我在练习画画，希望坚持。','{\"title\":\"画画练习\"}',true,%s::vector,'ready')", (record, user, '[' + ','.join(['0.1'] * 1024) + ']'))
    db.execute("INSERT INTO todo_registry(user_id,title,notes,current_status,origin_kind) VALUES(%s,'每天画画十分钟','从简单线条开始','in_progress','direct')", (user,))
    db.execute("INSERT INTO profile_snapshots(user_id,snapshot_type,period_month,overall_summary,version_no,is_current,invalidated) VALUES(%s,'monthly','2026-09','九月总结：画画让我慢慢找到兴趣。',1,true,false)", (user,))
    options.update(diaryEnabled=True, todosEnabled=True, mirrorEnabled=True)
    payload = {'clientRequestId': str(uuid.uuid4()), 'question': '如何坚持画画？', 'options': options}
    with_sources = finished(request(endpoint, payload, token), token)
    assert {s['kind'] for s in with_sources['sources']} == {'diary', 'todo', 'mirror'}
    # Revocation removes the persisted answer on its next read.
    db.execute('UPDATE chunks SET ai_memory_approved=false WHERE record_id=%s', (record,))
    stale = request('/tarot/analyses/' + with_sources['analysisId'], token=token)
    assert stale['status'] == 'stale' and not stale['text'] and not stale['sources']
    db.execute('UPDATE chunks SET ai_memory_approved=true WHERE record_id=%s', (record,))
    payload['clientRequestId'] = str(uuid.uuid4())
    latest = finished(request(endpoint, payload, token), token)
    assert request('/tarot/readings/' + reading['readingId'], token=token)['cardId'] == reading['cardId']
    assert request('/tarot/collection', token=token)['unlockedCount'] == 1
    (OUTPUT / 'test-account.json').write_text(json.dumps(auth), encoding='utf-8')
    (OUTPUT / 'test-other-account.json').write_text(json.dumps(other), encoding='utf-8')
    (OUTPUT / 'test-reading.json').write_text(json.dumps({'recordId': record, 'analysisId': latest['analysisId'], 'readingId': reading['readingId']}), encoding='utf-8')
print('PASS: concurrent daily, model missing, cross-account denial, real RPC, request replay, three sources, revoke/stale, same-card retry, daily-only collection')
