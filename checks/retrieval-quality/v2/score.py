"""New relevance version and explicit-user-action design probes; no relabeling of v1."""
from pathlib import Path
import hashlib
import json
import sys

here = Path(__file__).resolve().parent
out = Path(sys.argv[1])
v1 = here.parent / 'v1'
labels = json.loads((here / 'relevance-v2.json').read_text())
old = json.loads((v1 / 'baseline-from-6cc68b6/observations.json').read_text())
assert hashlib.sha256((v1 / 'catalog.json').read_bytes()).hexdigest() == labels['v1_catalog_sha256']
assert hashlib.sha256((v1 / 'baseline-from-6cc68b6/observations.json').read_bytes()).hexdigest() == labels['v1_observations_sha256']
assert hashlib.sha256((here / 'relevance-v2.json').read_bytes()).hexdigest() == (here / 'relevance-v2.sha256').read_text().split()[0]
queries = {q['id']: q for q in labels['queries']}
def mean(values):
    return sum(values) / len(values) if values else None
def recall(gold, ids):
    return len(set(gold) & set(ids[:5])) / len(gold) if gold else None
scores = []
unresolved = []
for row in old['results']:
    q = queries[row['query_id']]
    if q['status'] == 'clarification_required':
        unresolved.append({'id':q['id'], 'route':row['route'], 'status':q['status'], 'alternatives':q['alternatives']})
        continue
    gold = q['relevance_gold']; ids = [r['id'] for r in row['ranked']]
    scores.append({'id':q['id'], 'route':row['route'], 'gold':gold, 'top5':ids[:5],
                   'recall_at_5':recall(gold, ids), 'no_answer_false_positive':not gold and bool(ids),
                   'metadata_only_not_quote_evidence':q['quote_admissibility']['metadata_only_gold']})
relabel = {'scope':'new common relevance gold scored against unchanged v1 observations, not new execution or longitudinal improvement',
           'v1_sha256':labels['v1_catalog_sha256'], 'new_labels_sha256':hashlib.sha256((here / 'relevance-v2.json').read_bytes()).hexdigest(),
           'unresolved_ambiguities':unresolved, 'routes':{}, 'cases':scores}
for route in sorted({r['route'] for r in scores}):
    rows = [r for r in scores if r['route']==route]; positive=[r for r in rows if r['gold']]
    relabel['routes'][route]={'scored_queries':len(rows),'positive_queries':len(positive),
        'macro_recall_at_5':mean([r['recall_at_5'] for r in positive]),
        'empty_gold_queries':len(rows)-len(positive),'empty_gold_false_positives':sum(r['no_answer_false_positive'] for r in rows),
        'clarification_required_not_scored':sum(r['route']==route for r in unresolved)}

corpus = json.loads((here / 'question-probes.json').read_text())
observation = json.loads((out / 'question-observations.json').read_text())
assert observation['corpus_sha256']==hashlib.sha256((here / 'question-probes.json').read_bytes()).hexdigest()
records={r['id']:r for r in corpus['records']};cases={q['id']:q for q in corpus['queries']}
assert len(records)==len(corpus['records']) and len(cases)==len(corpus['queries'])
observations={r['id']:r for r in observation['results']};assert set(observations)==set(cases)
assert len(observations)==len(observation['results'])
measured=[];clarifications=[]
for id,q in cases.items():
    row=observations[id]
    if q['status']=='clarification_required':
        assert row['query_executed'] is False and 'after_scripted_confirmation' not in row
        clarifications.append({'id':id,'status':'not queried; explicit date decision missing','alternatives':q['alternatives']});continue
    assert row['query_executed'] and row['confirmed_query']==' '.join(q['confirmed_terms'])
    results={}
    for stage in ['raw_question','after_scripted_confirmation']:
        ranked=row[stage]['ranked'];ids=[r['id'] for r in ranked];assert len(ids)==len(set(ids))
        for r in ranked:
            source=records[r['id']];assert not source.get('deleted')
            assert r['revision']==(2 if 'revision2'in source else 1)
            assert r['original']==source.get('revision2',source['text'])
        results[stage]={'recall_at_5':recall(q['relevance_gold'],ids),'top5':ids[:5],'candidate_count':len(ids)}
    measured.append({'id':id,'question':q['question'],'confirmed_terms':q['confirmed_terms'],
        'user_action_provenance':q['user_action_provenance'],'gold':q['relevance_gold'],
        'all_terms_offered':row['all_terms_offered'],'truncated':row['truncated'],**results})
probe={'scope':'nonshipping design prototype + production original search; human choices are prewritten synthetic scripts, not automatic extraction accuracy or a user study',
       'prototype_revision':observation.get('prototype_revision',1),
       'corpus_sha256':observation['corpus_sha256'],'records':len(records),'questions':len(cases),
       'explicit_confirmation_scripts':len(measured),'clarification_required':clarifications,
       'mean_raw_question_recall_at_5':mean([r['raw_question']['recall_at_5'] for r in measured]),
       'mean_after_scripted_confirmation_recall_at_5':mean([r['after_scripted_confirmation']['recall_at_5'] for r in measured]),
       'scripts_whose_terms_all_occur_in_proposals':sum(r['all_terms_offered'] for r in measured),
       'missing_after_confirmation':[r['id'] for r in measured if r['after_scripted_confirmation']['recall_at_5']<1],
       'cases':measured,'release_quality_claim':'NONE; new UI/lifecycle not implemented or tested'}
(out / 'v2-relevance-rescore.json').write_text(json.dumps(relabel,ensure_ascii=False,indent=2)+'\n')
(out / 'design-probe-summary.json').write_text(json.dumps(probe,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'relevance_rescore':relabel['routes'],'new_probe':{k:v for k,v in probe.items() if k!='cases'}},ensure_ascii=False))
