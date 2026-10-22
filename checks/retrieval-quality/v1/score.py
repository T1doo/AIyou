"""Transparent source-group metrics; never called by production retrieval or normal CI."""
import csv
import hashlib
import json
import math
from pathlib import Path
import sys

catalog_path = Path(__file__).with_name('catalog.json')
raw = catalog_path.read_bytes()
catalog = json.loads(raw)
output = Path(sys.argv[1])
observed = json.loads((output / 'observations.json').read_text())
assert observed['catalog_sha256'] == hashlib.sha256(raw).hexdigest()
assert catalog_path.with_suffix('.sha256').read_text().split()[0] == observed['catalog_sha256']
records = {r['id']: r for r in catalog['records']}
queries = {q['id']: q for q in catalog['queries']}
assert len(records) == len(catalog['records']) and len(queries) == len(catalog['queries'])
assert all(r['support'] for r in records.values())
actual = {(r['query_id'], r['route']): r for r in observed['results']}
expected = {(q['id'], route) for q in queries.values() for route in q['gold']}
assert len(actual) == len(observed['results']) and set(actual) == expected
for q in queries.values():
    for route, gold in q['gold'].items():
        assert len(gold) == len(set(gold)) and set(gold) <= records.keys()
        assert set(q['grades'][route]) == set(gold)
        assert all(not records[g].get('deleted') for g in gold)

rows = []
failures = []
def mean(values):
    return sum(values) / len(values) if values else None

for (query_id, route), result in actual.items():
    q = queries[query_id]
    ranked = result['ranked']
    ids = [r['id'] for r in ranked]
    assert len(ids) == len(set(ids))
    assert result['proof_current'] and result['stage'] in ('READY', 'EMPTY')
    for r in ranked:
        source = records[r['id']]
        assert not source.get('deleted')
        assert r['revision'] == (2 if 'revision2' in source else 1)
    gold = set(q['gold'][route])
    grades = q['grades'][route]
    # Evidence output is already budgeted. Use the actual upstream matcher observations,
    # with the production candidate contract, to distinguish pre-budget loss from top5 loss.
    before = ids
    if route == 'evidence':
        before = [r['id'] for r in actual[query_id, 'original']['ranked']
                  if r['original_match'] and not records[r['id']].get('ai_none')]
    top = ids[:5]
    recall = len(gold.intersection(top)) / len(gold) if gold else None
    full_recall = len(gold.intersection(before)) / len(gold) if gold else None
    ceiling = min(5, len(gold)) / len(gold) if gold else None
    attainable = min(5, len(gold.intersection(before))) / len(gold) if gold else None
    def dcg(values):
        return sum((2 ** grades.get(g, 0) - 1) / math.log2(i + 2) for i, g in enumerate(values[:5]))
    ideal = sorted(gold, key=lambda g: (-grades[g], g))
    ndcg = dcg(top) / dcg(ideal) if gold else None
    kinds = []
    if gold - set(before):
        kinds.append('candidate_absent')
    if gold and recall < attainable:
        kinds.append('ranking_or_evidence_budget')
    if gold and recall < 1 and recall == ceiling:
        kinds.append('top5_capacity_not_ranking_defect')
    if gold and ndcg < 1 and len(gold.intersection(top)) == min(5, len(gold)):
        kinds.append('graded_order_loss')
    if not gold and top:
        kinds.append('no_answer_false_positive')
    row = {'query_id': query_id, 'route': route, 'category': q['category'], 'lane': q['lane'],
           'gold_count': len(gold), 'candidate_count': len(before), 'returned_count': len(ids),
           'recall_at_5': recall, 'full_candidate_recall': full_recall,
           'oracle_top5_ceiling': ceiling, 'ndcg_at_5': ndcg,
           'no_answer_false_positive': bool(not gold and top), 'failure_kinds': kinds,
           'top5': top, 'missing_from_candidates': sorted(gold - set(before)),
           'missing_from_top5': sorted(gold - set(top))}
    rows.append(row)
    if kinds:
        failures.append({**row, 'query': q['query'], 'from': q['from'], 'through': q['through'],
                         'rationale': q['rationale'], 'gold': sorted(gold),
                         'records': {g: records[g] for g in sorted(gold | set(top))}})

def aggregate(selected):
    positive = [r for r in selected if r['gold_count']]
    negative = [r for r in selected if not r['gold_count']]
    return {'queries': len(selected), 'positive_queries': len(positive), 'empty_gold_queries': len(negative),
            'macro_recall_at_5': mean([r['recall_at_5'] for r in positive]),
            'macro_full_candidate_recall': mean([r['full_candidate_recall'] for r in positive]),
            'macro_oracle_top5_ceiling': mean([r['oracle_top5_ceiling'] for r in positive]),
            'macro_ndcg_at_5': mean([r['ndcg_at_5'] for r in positive]),
            'empty_gold_false_positive_count': sum(r['no_answer_false_positive'] for r in negative),
            'failure_query_counts': {k: sum(k in r['failure_kinds'] for r in selected) for k in
                ['candidate_absent', 'ranking_or_evidence_budget', 'top5_capacity_not_ranking_defect',
                 'graded_order_loss', 'no_answer_false_positive']}}

summary = {'catalog_sha256': observed['catalog_sha256'], 'production_base_sha': observed['production_base_sha'],
           'records': len(records), 'labeled_queries': len(queries),
           'population': catalog['population'], 'authorship': catalog['authorship'],
           'metrics': 'Macro Recall@5 over nonempty independently listed source-group gold; no-answer cases separate. No pooling routes.',
           'routes': {route: {'all': aggregate([r for r in rows if r['route'] == route]),
                             'lanes': {lane: aggregate([r for r in rows if r['route'] == route and r['lane'] == lane])
                                       for lane in sorted({r['lane'] for r in rows})},
                             'categories': {c: aggregate([r for r in rows if r['route'] == route and r['category'] == c])
                                            for c in sorted({r['category'] for r in rows})}}
                      for route in sorted({r['route'] for r in rows})}, 'cases': rows}
(output / 'baseline.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
(output / 'failures.json').write_text(json.dumps(failures, ensure_ascii=False, indent=2) + '\n')
with (output / 'case-metrics.csv').open('w', newline='') as f:
    fields = ['query_id', 'route', 'category', 'lane', 'gold_count', 'candidate_count', 'returned_count',
              'recall_at_5', 'full_candidate_recall', 'oracle_top5_ceiling', 'ndcg_at_5', 'no_answer_false_positive']
    writer = csv.DictWriter(f, fieldnames=fields, extrasaction='ignore'); writer.writeheader(); writer.writerows(rows)
print(json.dumps({'records': len(records), 'queries': len(queries), 'routes': {
    route: {lane: value for lane, value in group.items() if lane != 'categories'}
    for route, group in summary['routes'].items()}}, ensure_ascii=False))
