"""Verify explicit YAML reloads against owned client wire receipts and typed exports."""
import hashlib,json,re
from pathlib import Path
from rig_settings import values
KEYS=('lod.distance.default_chunks','generation.concurrency.global','generation.concurrency.per_player')
DISK='storage.disk.max_concurrent_reads'
SESSION=re.compile(rb'Server session config received \(protocol v(\d+), LOD distance: (\d+) chunks, enabled: (true|false)\)')
LABELS=('saved-only','applied','unrelated','scope-restored','unchanged','rejected','restored')
def target_values(value):
    if set(value)!=set(KEYS) or any(type(value[k]) is not int for k in KEYS):raise ValueError('accepted measured numeric contract required; tokens remain nonexecutable')
    if not 1<=value[KEYS[0]]<=2048 or not 1<=value[KEYS[2]]<=value[KEYS[1]]<=512:raise ValueError('target outside supported numeric bounds')
    return value
def sha(value):return hashlib.sha256(value).hexdigest()
def expectations(original,baseline,target):
    applied={**baseline,**target};alternate=3 if baseline[DISK]!=3 else 4
    unrelated={**applied,DISK:alternate};restored={**baseline,DISK:alternate}
    return [applied,applied,unrelated,restored,restored,None,original], [baseline[KEYS[0]],target[KEYS[0]],target[KEYS[0]],baseline[KEYS[0]],baseline[KEYS[0]],baseline[KEYS[0]],original[KEYS[0]]], [0,1,0,1,0,0,int(original[KEYS[0]]!=baseline[KEYS[0]])]
def verify_data(receipt,original,baseline,phase_files,client_log,snapshot_checker):
    target=target_values(receipt['target'])
    if receipt['status']!='passed' or [r['label'] for r in receipt['phases']]!=list(LABELS):raise ValueError('complete phase sequence required')
    synthetic={KEYS[0]:1 if target[KEYS[0]]!=1 else 2,KEYS[1]:1 if target[KEYS[1]]!=1 else 2,KEYS[2]:1}
    if baseline!={**original,**synthetic}:raise ValueError('synthetic baseline changed unrelated settings')
    expected,radii,counts=expectations(original,baseline,target);last=None
    for row,wanted,radius,count in zip(receipt['phases'],expected,radii,counts):
        label=row['label'];config_raw,snapshot_raw,summary_raw=phase_files[label]
        if (sha(config_raw),sha(snapshot_raw),sha(summary_raw))!=(row['config_sha256'],row['snapshot_sha256'],row['summary_sha256']):raise ValueError('captured bytes changed: '+label)
        if wanted is None:
            try:values(config_raw.decode())
            except RuntimeError:pass
            else:raise ValueError('rejected phase must contain invalid operator edit')
        elif values(config_raw.decode())!=wanted:raise ValueError('persisted config/scope differs: '+label)
        snapshot=json.loads(snapshot_raw);generation=original['generation.enabled']
        snapshot_checker(snapshot,summary_raw.decode(),generation,generation)
        if snapshot['lodDistanceChunks']!=radius:raise ValueError('effective radius differs: '+label)
        start,end=row['client_start'],row['client_end']
        if type(start) is not int or type(end) is not int or not 0<=start<=end<=len(client_log) or last is not None and start!=last:raise ValueError('noncontiguous client log windows')
        if row['settled_seconds']<3:raise ValueError('bounded late-receipt observation missing')
        segment=client_log[start:end]
        if sha(segment)!=row['client_sha256']:raise ValueError('original client log interval changed')
        if SESSION.findall(segment)!=[(b'20',str(radius).encode(),b'true')]*count:raise ValueError('actual session receipt count/value differs: '+label)
        last=end
    if SESSION.search(client_log[last:]):raise ValueError('late unexpected session receipt after final window')
    if b'unexpected session replacement in one-phase client' in client_log:raise ValueError('native world/connection changed')
    return {'status':'passed','phases':len(LABELS),'wire_repushes':sum(counts),'generation_running':original['generation.enabled']}
def verify(root,receipt_path):
    from rig import regular,inside
    from server_control_smoke import check_snapshot
    root=Path(root).resolve();receipt_path=regular(Path(receipt_path).resolve());directory=receipt_path.parent
    if not receipt_path.is_relative_to(root/'evidence'):raise ValueError('run-owned numeric receipt required')
    receipt=json.loads(receipt_path.read_text());manifest=json.loads(regular(root/'manifest.json').read_text());runtime=json.loads(regular(root/'runtime.json').read_text())
    for key in ('run_id','run_hash','profile_hash','scenario_hash'):
        if receipt[key]!=manifest[key]:raise ValueError('run identity mismatch')
    if receipt['target']!=runtime['settings_contract']['numeric_target']:raise ValueError('frozen target differs')
    log=regular(root/'settings-client.private.log')
    if log.stat().st_size>32*1024*1024:raise ValueError('client log exceeds bound')
    files={}
    for label in LABELS:
        paths=[regular(directory/(label+suffix)) for suffix in ('.config.yaml','.json','.txt')]
        if any(path.stat().st_size>65536 for path in paths):raise ValueError('phase capture exceeds bound')
        files[label]=tuple(path.read_bytes() for path in paths)
    result=verify_data(receipt,values(regular(directory/'original-config.yaml')),values(regular(directory/'baseline-config.yaml')),files,log.read_bytes(),check_snapshot)
    for row in receipt['phases']:
        steps=receipt['steps'][row['command_start']:row['command_end']]
        suffixes=['diagnostics export'] if row['label']=='saved-only' else ['reload','diagnostics export']
        if [s['command'] for s in steps]!=[receipt['command_root']+' '+x for x in suffixes]:raise ValueError('numeric phase command sequence differs')
    for step in receipt['steps']:
        queued=step['queue_result'];actual=json.loads(regular(inside(root,'commands/results/'+queued['request_id']+'.json')).read_text())
        if actual!=queued or actual.get('status')!='response_observed':raise ValueError('original owned command result changed')
        with regular(root/'server.private.log').open('rb') as stream:stream.seek(actual['log_offset']);body=stream.read(256*1024)
        if step['expected_response'].encode() not in body:raise ValueError('owned console acknowledgement absent')
    return result
