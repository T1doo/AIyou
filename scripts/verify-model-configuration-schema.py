#!/usr/bin/env python3
"""Require actual KSP v13 bytes and unchanged v2..v12 predecessors; never fabricate Room exports."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root=Path(__file__).resolve().parents[1]
directory=root/'core/data/schemas/com.t1doo.aiyou.data.RecordsDatabase'
export=directory/'13.json'
asset=root/'core/data/src/androidTest/assets/records-schema-v13.json'
receipt=root/'.local-evidence/d04-model-configuration/schema-generation.json'
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()

def digest(raw):return hashlib.sha256(raw).hexdigest()
def checkout(path):return subprocess.check_output(['git','show',head+':'+path.relative_to(root).as_posix()],cwd=root)
def historical():
    result={}
    for version in range(2,13):
        p=directory/f'{version}.json';f=asset.parent/f'records-schema-v{version}.json'
        raw=p.read_bytes()
        if raw!=f.read_bytes() or raw!=checkout(p) or raw!=checkout(f):raise SystemExit(f'Historical v{version} drift; preserve and stop')
        result[str(version)]=digest(raw)
    return result
def normalize(sql):return re.sub(r'\s+','',sql.replace('`','').replace('"','')).lower()
def validate(raw):
    database=json.loads(raw)['database'];old=json.loads((directory/'12.json').read_bytes())['database']
    current={e['tableName']:e for e in database['entities']};prior={e['tableName']:e for e in old['entities']}
    assert database['version']==13 and len(current)==20 and set(current)==set(prior)|{'model_configurations'}
    assert all(current[name]==entity for name,entity in prior.items())
    entity=current['model_configurations']
    fields=[('id','TEXT',True),('version','INTEGER',True),('lastMutationId','TEXT',True),('deleted','INTEGER',True),('payload','BLOB',False)]
    expected=[dict({'fieldPath':n,'columnName':n,'affinity':a},**({'notNull':True} if required else {})) for n,a,required in fields]
    assert entity['fields']==expected and entity['primaryKey']=={'autoGenerate':False,'columnNames':['id']}
    assert not entity.get('indices') and not entity.get('foreignKeys')
    expected_sql='CREATE TABLE IF NOT EXISTS model_configurations (id TEXT NOT NULL, version INTEGER NOT NULL, lastMutationId TEXT NOT NULL, deleted INTEGER NOT NULL, payload BLOB, PRIMARY KEY(id))'
    assert normalize(entity['createSql'].replace('${TABLE_NAME}','model_configurations'))==normalize(expected_sql)
    assert re.fullmatch('[0-9a-f]{32}',database['identityHash']) and database.get('views',[])==old.get('views',[])
    assert database['setupQueries']==['CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)',
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '"+database['identityHash']+"')"]

if sys.argv[1:]==['--capture']:
    prior=historical()
    if export.exists()!=asset.exists():raise SystemExit('Partial v13 schema/fixture state; preserve and stop')
    if export.exists():
        raw=export.read_bytes();validate(raw)
        if raw!=asset.read_bytes() or raw!=checkout(export) or raw!=checkout(asset):raise SystemExit('v13 checkout drift')
        mode,expected='CHECKED_IN',digest(raw)
    else:
        if subprocess.check_output(['git','ls-files','--',str(export.relative_to(root)),str(asset.relative_to(root))],cwd=root):raise SystemExit('Tracked v13 bytes missing')
        mode,expected='BOOTSTRAP',None
    receipt.parent.mkdir(parents=True,exist_ok=True)
    receipt.write_text(json.dumps({'head':head,'mode':mode,'expected_sha256':expected,'historical':prior})+'\n')
elif sys.argv[1:]==['--verify']:
    state=json.loads(receipt.read_text());assert state['head']==head and state['historical']==historical()
    raw=export.read_bytes();validate(raw)
    if state['mode']=='CHECKED_IN':
        assert digest(raw)==state['expected_sha256'] and raw==asset.read_bytes() and raw==checkout(export) and raw==checkout(asset)
    else:
        assert state['mode']=='BOOTSTRAP' and not asset.exists();asset.write_bytes(raw)
    state.update(generated_sha256=digest(raw),bytes=len(raw),scope='actual KSP export; BOOTSTRAP bytes require commit and another exact-head natural CI' if state['mode']=='BOOTSTRAP' else 'exact committed KSP bytes verified')
    receipt.write_text(json.dumps(state,indent=2)+'\n');print('MODEL_CONFIGURATION_SCHEMA='+json.dumps(state,separators=(',',':')))
else:raise SystemExit('Usage: verify-model-configuration-schema.py --capture|--verify')
