"""Synthetic acceptance against real Java, PostgreSQL, Python RPC and a local model stub.

Requires MIRROR_TAROT_TEST_DSN (psycopg), MIRROR_TAROT_TEST_OUTPUT (outside git).
Optional MIRROR_TAROT_TEST_TRACE_DIR verifies sanitized RPC/model card observations.
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
    catalog = request('/tarot/cards', token=token)
    assert catalog['catalogVersion'] == 'v2' and catalog['activeCount'] == 78
    cards = {card['id']: card for card in catalog['cards']}
    assert len(cards) == 78
    assert {family: sum(card['category'] == family for card in cards.values()) for family in
            ('major', 'wands', 'cups', 'swords', 'pentacles')} == {'major': 22, 'wands': 14, 'cups': 14, 'swords': 14, 'pentacles': 14}
    assert all(card['reversedKeyword'] and card['reversedMeaning'] and card['reversedAction'] for card in cards.values())
    draw_requests = [{'clientRequestId': str(uuid.uuid4())} for _ in range(12)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        draws = list(pool.map(lambda payload: request('/tarot/daily/draw', payload, token), draw_requests))
    assert len({(draw['draw']['id'], draw['draw']['cardId'], draw['draw']['orientation']) for draw in draws}) == 1
    assert draws[0]['draw']['orientation'] in ('upright', 'reversed')
    assert all(result['draw'] == draws[0]['draw'] for result in draws), 'Concurrent daily responses changed persisted timestamp'
    assert request('/tarot/daily/draw', draw_requests[0], token)['draw'] == draws[0]['draw']
    assert request('/tarot/collection', token=token)['entries'][0]['dailyDrawCount'] == 1
    directions = {}
    for _ in range(30):
        draw_request = {'clientRequestId': str(uuid.uuid4())}
        candidate = request('/tarot/readings', draw_request, token)
        for recovered in (request('/tarot/readings', draw_request, token), request('/tarot/readings/' + candidate['readingId'], token=token)):
            assert candidate == recovered, 'Question retry changed persisted card/direction/timestamp'
        directions.setdefault(candidate['orientation'], candidate)
        if len(directions) == 2:
            break
    assert set(directions) == {'upright', 'reversed'}, 'Both directions were not sampled in 30 draws'
    reading = directions['reversed']
    options = {'diaryEnabled': False, 'diaryDays': 7, 'todosEnabled': False, 'mirrorEnabled': False}
    # Missing model is explicit, while card and collection still work.
    request('/tarot/readings/' + reading['readingId'] + '/analyses',
            {'clientRequestId': str(uuid.uuid4()), 'question': '如何开始画画？', 'options': options}, token, expected=400)
    request('/tarot/readings/' + reading['readingId'], token=other['token'], expected=404)
    generic = finished(request('/tarot/readings/' + reading['readingId'] + '/analyses',
                       {'clientRequestId': str(uuid.uuid4()), 'question': '', 'options': options}, token), token)
    card = cards[reading['cardId']]
    assert generic['text'] == card['reversedMeaning'] + '\n' + card['reversedAction']
    request('/settings', {'aiProtocol': 'openai', 'aiProvider': 'openai', 'aiApiKey': 'synthetic-only',
                         'aiBaseUrl': 'http://127.0.0.1:18089/v1', 'aiModel': 'tarot-stub'}, token, 'PUT')
    endpoint = '/tarot/readings/' + reading['readingId'] + '/analyses'
    payload = {'clientRequestId': str(uuid.uuid4()), 'question': '如何开始画画？', 'options': options}
    analysis = finished(request(endpoint, payload, token), token)
    assert analysis['analysisId'] == request(endpoint, payload, token)['analysisId']
    assert not analysis['sources']
    upright = directions['upright']
    finished(request('/tarot/readings/' + upright['readingId'] + '/analyses',
                     {'clientRequestId': str(uuid.uuid4()), 'question': '如何开始画画？', 'options': options}, token), token)
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
    versions = request(endpoint, token=token)
    assert len(versions) == 4 and versions[0]['analysisId'] == latest['analysisId']
    assert all(version['createdAt'] and version['completedAt'] for version in versions)
    assert {version['status'] for version in versions} == {'completed', 'stale'}
    request(endpoint, token=other['token'], expected=404)
    for version in versions:
        saved = request('/tarot/analyses/' + version['analysisId'], token=token)
        if saved['status'] == 'stale':
            assert not saved['text'] and not saved['sources']
    first_page = request('/tarot/history?limit=2&offset=0', token=token)
    all_events = []
    for offset in range(0, first_page['total'], 2):
        page = request(f'/tarot/history?limit=2&offset={offset}', token=token)
        assert page['offset'] == offset and page['limit'] == 2
        all_events.extend(page['items'])
        assert page['hasMore'] == (offset + len(page['items']) < page['total'])
    assert len(all_events) == len({(item['kind'], item['id']) for item in all_events}) == first_page['total']
    assert {item['kind'] for item in all_events} == {'daily', 'question'}
    assert all(item['drawDate'] and item['createdAt'].endswith('+08:00') and item['orientation'] in ('upright', 'reversed') for item in all_events)
    event = next(item for item in all_events if item['id'] == reading['readingId'])
    assert event['latestAnalysisId'] == latest['analysisId'] and event['orientation'] == 'reversed'
    assert request('/tarot/history?limit=20&offset=0', token=other['token'])['total'] == 0
    trace_dir = os.getenv('MIRROR_TAROT_TEST_TRACE_DIR')
    for filename in (('grpc-cards.jsonl', 'model-cards.jsonl') if trace_dir else ()):
        traces = [json.loads(line) for line in (Path(trace_dir) / filename).read_text(encoding='utf-8').splitlines()]
        assert {trace['orientation'] for trace in traces} == {'upright', 'reversed'}
        for trace in traces:
            source = cards[trace['cardId']] if 'cardId' in trace else next(card for card in cards.values() if card['name'] == trace['name'])
            prefix = 'reversed' if trace['orientation'] == 'reversed' else ''
            assert trace['meaning'] == source['reversedMeaning' if prefix else 'meaning']
            assert trace['action'] == source['reversedAction' if prefix else 'action']
            assert trace['category'] == source['category']
    (OUTPUT / 'runtime-summary.json').write_text(json.dumps({'catalogCount': len(cards), 'orientations': sorted(directions), 'historyTotal': first_page['total'], 'savedVersions': len(versions), 'strictCreateReplayTimestampEquality': True, 'staleVersionHidden': True, 'grpcAndModelDirectionVerified': bool(trace_dir)}, indent=2), encoding='utf-8')
    (OUTPUT / 'test-account.json').write_text(json.dumps(auth), encoding='utf-8')
    (OUTPUT / 'test-other-account.json').write_text(json.dumps(other), encoding='utf-8')
    (OUTPUT / 'test-reading.json').write_text(json.dumps({'recordId': record, 'analysisId': latest['analysisId'], 'readingId': reading['readingId']}), encoding='utf-8')
print('PASS: 78 cards/five families, both persisted directions, concurrent daily, immutable replay, paginated history, four saved analysis versions, cross-account denial, Java/Python RPC, three sources, revoke/stale, daily-only collection')
if trace_dir:
    print('PASS: sanitized observations verified direction/category/meaning/action at both gRPC and model boundaries')
