"""Score frozen paired observations; no query construction, term selection or relabeling."""
from pathlib import Path
from collections import Counter
import csv
import hashlib
import json
import sys

root = Path(__file__).resolve().parent / 'frozen'
out = Path(sys.argv[1])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
load = lambda p: json.loads(p.read_text())
freeze = load(root / 'execution-freeze.json')
for item in freeze['files']:
    assert sha(root / item['path']) == item['sha256'], item['path']
roles = load(root / 'role-receipts.json')
assert roles['status'] == 'independent_agent_roles_completed_before_first_query'
assert len(roles['gold_actors']) == 2 and len(set(roles['gold_actors'])) == 2
assert len(roles['choice_actors']) == 2 and len(set(roles['choice_actors'])) == 2
qdata = load(root / 'questions.json')['questions']
questions = {q['id']: q for q in qdata}
records = {r['id']: r for r in load(root / 'corpus.json')['records']}
gold = {r['id']: r for r in load(root / 'gold-adjudicated.json')['labels']}
assert set(gold) == set(questions) and len(gold) == len(qdata) == 24
raw = load(out / 'observations.json')
assert raw['execution_manifest_sha256'] == sha(root / 'execution-freeze.json')
observed = {(r['question_id'], r['route']): r for r in raw['results']}
assert len(observed) == len(raw['results']) == 48
routes = ['direct_keywords', 'assisted_confirmed']
assert set(observed) == {(q, r) for q in questions for r in routes}
rows = []
for qid, q in questions.items():
    g = gold[qid]
    assert g['status'] == q['resolution_status']
    gids = {x['id'] for x in g['gold']}
    assert len(gids) == len(g['gold'])
    for x in g['gold']:
        r = records[x['id']]
        assert r['state'] == 'current' and r['revision'] == x['revision']
    for route in routes:
        r = observed[qid, route]
        ids = [x['id'] for x in r['ranked']]
        assert len(ids) == len(set(ids))
        for x in r['ranked']:
            source = records[x['id']]
            assert source['state'] == 'current' and x['revision'] == source['revision'] and x['original'] == source['text']
            assert all(a in source['aliases'] for a in x['alias_matches'])
        if r['stage'] == 'READY':
            assert r['publication_commit_ack'] is True and r['query_count'] == 1
            assert len(r['resolved']) == min(5, len(ids))
            assert all(x['published'] and x['commit_ack'] for x in r['resolved'])
            assert [x['id'] for x in r['resolved']] == ids[:5]
        else:
            assert not ids and not r['resolved']
        if route == 'assisted_confirmed':
            assert r['sql_before_confirm'] == 0 and r['commits'] <= 1
        unresolved = q['resolution_status'] == 'clarification_required'
        if unresolved:
            assert r['query_count'] == r['commits'] == 0 and r['stage'] == 'ABSTAINED'
        scorable = not unresolved and bool(gids)
        recall = len(set(ids[:5]) & gids) / len(gids) if scorable else None
        full = len(set(ids) & gids) / len(gids) if scorable else None
        plan = r['plan']
        rows.append(dict(id=qid, route=route, stratum=q['stratum'], status=q['resolution_status'],
                         stage=r['stage'], gold=sorted(gids), top5=ids[:5], candidates=len(ids),
                         recall_at_5=recall, full_candidate_recall=full,
                         theoretical_ceiling=min(5, len(gids)) / len(gids) if scorable else None,
                         empty_gold_false_return=not unresolved and not gids and bool(ids),
                         term_edit_needed=bool(plan.get('term_edit_required', False)) if route == 'assisted_confirmed' else None,
                         actual_term_edit='terms' in r['actions'],
                         selected_fragments=len(plan.get('selected', [])),
                         actual_manual_date_entry='from/through' in r['actions'],
                         reason=plan.get('rationale', plan.get('reason', '')),
                         direct_input_terms=plan.get('terms') if route == 'direct_keywords' else None))

def mean(v):
    return sum(v) / len(v) if v else None

def aggregate(cases):
    resolved = [x for x in cases if x['status'] == 'resolved']
    positive = [x for x in resolved if x['gold']]
    ready = [x for x in resolved if x['stage'] == 'READY']
    empty = sum(x['candidates'] == 0 for x in ready)
    empty_gold = [x for x in resolved if not x['gold']]
    return dict(total_questions=len(cases), resolved=len(resolved), positive_gold=len(positive),
                clarification_required=len(cases)-len(resolved),
                macro_recall_at_5=mean([x['recall_at_5'] for x in positive]),
                macro_full_candidate_recall=mean([x['full_candidate_recall'] for x in positive]),
                mean_theoretical_ceiling=mean([x['theoretical_ceiling'] for x in positive]),
                stages=dict(Counter(x['stage'] for x in cases)),
                no_candidates_end_to_end={'numerator':empty, 'denominator':len(resolved), 'rate':empty/len(resolved) if resolved else None},
                no_candidates_ready_only={'numerator':empty, 'denominator':len(ready), 'rate':empty/len(ready) if ready else None},
                empty_gold={'questions':len(empty_gold), 'returned_any':sum(x['empty_gold_false_return'] for x in empty_gold)},
                term_edit_need={'numerator':sum(x['term_edit_needed'] is True for x in resolved), 'denominator':len(resolved), 'rate':sum(x['term_edit_needed'] is True for x in resolved)/len(resolved) if resolved else None},
                actual_term_edits=sum(x['actual_term_edit'] for x in resolved),
                actual_manual_date_entries=sum(x['actual_manual_date_entry'] for x in resolved))
summary = dict(scope='frozen synthetic 24-question paired challenge; independent agent annotations/choices, same-model bias; no human or release-quality claim',
               now_regression_set=True, no_further_unseen_holdout_claim=True,
               execution_freeze_sha256=sha(root / 'execution-freeze.json'), routes={}, strata={}, cases=rows)
for route in routes:
    selection = [x for x in rows if x['route'] == route]
    summary['routes'][route] = aggregate(selection)
    summary['strata'][route] = {s:aggregate([x for x in selection if x['stratum'] == s]) for s in sorted({q['stratum'] for q in qdata})}
summary['paired_recall_delta_assisted_minus_direct'] = summary['routes'][routes[1]]['macro_recall_at_5']-summary['routes'][routes[0]]['macro_recall_at_5']
summary['limitations'] = ['No statistical significance or 85% release/real-user claim', 'Term edit need is this blind action plan, not minimum over all possible choices', 'No generated answer entailment claim', 'Real OCR, device audio, native SQLCipher device, TalkBack, performance NOT RUN']
(out / 'quality.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
with (out / 'cases.csv').open('w', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
print(json.dumps({k:v for k,v in summary.items() if k not in ['cases','strata']}, ensure_ascii=False, indent=2))
