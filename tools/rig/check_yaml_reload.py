#!/usr/bin/env python3
"""Check real owner-boundary settings observations and run-owned console receipts."""
import hashlib,json,re
from pathlib import Path
from rig import regular,digest

def bound_platform(receipt,scenario):
    platform=scenario.get('server_platform')
    if platform not in ('fabric','paper','folia') or receipt.get('platform')!=platform:
        raise ValueError('receipt platform differs from frozen scenario')
    return platform

def deposits(row):
    match=re.search(r'(\d+) deposited',row.get('backfill_status',''))
    return int(match.group(1)) if match else 0

def check(rows,phases,platform):
    errors=[]
    def require(ok,why):
        if not ok:errors.append(why)
    require(not any(r.get('event')=='failure' or r.get('overflow') for r in rows),'observer failed or overflowed')
    require(any(r.get('event')=='closed' for r in rows),'observer writer did not close')
    require({r.get('kind') for r in rows if r.get('event')=='applied'}=={'service','generation'},'exact product instrumentation absent')
    required={'initial','off','drained','quiet','resumed','serving','restart_pending','restart_retained','restart_reverted'}
    if platform=='fabric':required|={'backfill_low','backfill_low_end','backfill_high','backfill_high_end','backfill_stopped','backfill_quiet','backfill_resumed'}
    require(required<=phases.keys(),'required transition phases absent')
    if errors:return errors
    order=['initial','off','drained','quiet','resumed','serving']
    if platform=='fabric':order+=['backfill_low','backfill_low_end','backfill_high','backfill_high_end','backfill_stopped','backfill_quiet','backfill_resumed']
    order+=['restart_pending','restart_retained','restart_reverted']
    require(all(type(phases[key]) is int and 0<=phases[key]<len(rows) for key in order),'phase index invalid')
    if errors:return errors
    require(all(phases[a]<phases[b] for a,b in zip(order,order[1:])),'phase indices are reused or reordered')
    p={key:rows[index] for key,index in phases.items()}
    require(all(p[key].get('event')==('generation_policy' if key=='off' else 'sample') for key in order),'phase event kind invalid')
    require(all(p[a]['time_ns']<p[b]['time_ns'] for a,b in zip(order,order[1:])),'phase timestamps are reused or reordered')
    initial,off,drained,quiet,resumed,serving=(p[k] for k in ('initial','off','drained','quiet','resumed','serving'))
    require(initial.get('players')==2 and initial['sent']>0,'two real registered serving clients absent')
    require(off['event']=='generation_policy' and off['enabled'] is False and off['active']>0,'disable lacked exact positive admitted-work premise')
    require(drained['enabled'] is False and drained['active']==0,'admitted generation did not drain')
    require(drained['submitted']==drained['completed']+drained['timeouts']+drained['removed'],'generation conservation failed')
    require(drained['timeouts']==initial['timeouts'] and drained['removed']==initial['removed'],'drain timed out or removed admitted work')
    require(quiet['enabled'] is False and quiet['active']==0 and quiet['time_ns']-drained['time_ns']>=2_000_000_000 and quiet['submitted']==drained['submitted'],'disabled generation admitted new work')
    require(resumed['enabled'] is True and resumed['revision']>off['revision'] and resumed['completed']>quiet['completed'],'re-enabled generation made no progress')
    require(serving['sent']>quiet['sent'] and serving['players']==2,'normal serving did not continue')
    for phase in ('restart_pending','restart_retained','restart_reverted'):
        require(p[phase]['reader_threads']==initial['reader_threads'],'restart-only physical reader pool changed')
    require(p['restart_pending']['reader_restart_pending'] and p['restart_retained']['reader_restart_pending'] and not p['restart_reverted']['reader_restart_pending'],'restart pending state did not retain/revert')
    require(p['restart_retained']['revision']>p['restart_pending']['revision'],'pending retention lacked unrelated accepted revision')
    if platform=='fabric':
        require(drained.get('tracked_positions',0)>0 and drained.get('tracked_tickets')==0 and drained.get('deferred')==0,'observed native generation tickets did not drain')
        low,end,high,high_end,stopped,bquiet,restarted=(p[k] for k in ('backfill_low','backfill_low_end','backfill_high','backfill_high_end','backfill_stopped','backfill_quiet','backfill_resumed'))
        require(low['backfill_running'] and low['backfill_rate']==1 and low['backfill_enabled'],'low backfill policy was not adopted')
        seconds=(end['time_ns']-low['time_ns'])/1e9
        require(seconds>=3 and end['backfill_worker']==low['backfill_worker'] and 0<deposits(end)-deposits(low)<=seconds+2,'low-rate real deposit premise/bound failed')
        require(high['backfill_rate']==16 and high['backfill_revision']>low['backfill_revision'] and high['backfill_worker']==low['backfill_worker'],'live worker rate update absent')
        high_seconds=(high_end['time_ns']-high['time_ns'])/1e9
        high_deposits=deposits(high_end)-deposits(high)
        require(high_end['backfill_worker']==high['backfill_worker'] and high_seconds>=3 and high_seconds+2<high_deposits<=16*high_seconds+16,'higher-rate actual pacing did not increase within its bound')
        require(stopped['backfill_running'] is False and stopped['backfill_enabled'] is False,'backfill stop did not settle')
        require(bquiet['time_ns']-stopped['time_ns']>=2_000_000_000 and not bquiet['backfill_running'] and deposits(bquiet)==deposits(stopped),'backfill deposited after stop')
        require(restarted['backfill_running'] and restarted['backfill_enabled'] and restarted['backfill_worker']!=stopped['backfill_worker'] and deposits(restarted)>0,'new backfill worker did not resume progress')
    require(all(p[b]['time_ns']<p[a]['time_ns'] for b,a in zip(['initial','off','drained','quiet','resumed'],['off','drained','quiet','resumed','serving'])),'generation transition ordering invalid')
    return errors

def inspect(root):
    root=Path(root);manifest=json.loads(regular(root/'manifest.json').read_text())
    receipt=json.loads(regular(root/'evidence/yaml-reload.json').read_text())
    rows=[json.loads(line) for line in regular(root/'evidence/settings-events.jsonl').read_text().splitlines()]
    errors=[]
    scenario=json.loads(regular(root/'scenario.json').read_text())
    runtime=json.loads(regular(root/'runtime.json').read_text())
    for name,document in [('scenario',scenario),('runtime',runtime)]:
        if digest(document)!=manifest.get(name+'_hash'):errors.append('frozen '+name+' hash changed')
    try: platform=bound_platform(receipt,scenario)
    except ValueError as error: return {'status':'failed','errors':[str(error)],'test_count':0}
    for key in ('run_id','run_hash','profile_hash','scenario_hash'):
        if receipt.get(key)!=manifest.get(key):errors.append('receipt identity mismatch: '+key)
    if receipt.get('status')!='passed':errors.append('driver did not complete: '+receipt.get('error','unknown'))
    if any(row.get('run_id',manifest['run_id'])!=manifest['run_id'] for row in rows):errors.append('observer run identity mismatch')
    try:errors+=check(rows,receipt.get('phases',{}),platform)
    except (KeyError,IndexError,TypeError,ValueError) as e:errors.append('malformed transition evidence: '+str(e))
    try:
        from rig import inside
        from server_control_smoke import verify_receipt
        verify_receipt(root,inside(root,receipt['control_receipt']))
    except (KeyError,TypeError,ValueError,OSError) as error:errors.append('invalid/no-op/retry evidence absent: '+str(error))
    for subject in 'AB':
        server_text=regular(root/'server.private.log').read_text(errors='replace')
        client_text=regular(root/('client-'+subject+'.private.log')).read_text(errors='replace')
        if 'Player RigSubject'+subject+' registered for LSS LOD request processing' not in server_text or 'Server session config received (protocol v20,' not in client_text:
            errors.append('real v20 handshake absent: '+subject)
    if not receipt.get('commands'):errors.append('owned command receipts absent')
    for step in receipt.get('commands',[]):
        result=step['queue_result'];actual=json.loads(regular(root/'commands/results'/(result['request_id']+'.json')).read_text())
        if actual!=result or actual.get('status')!='response_observed':errors.append('owned command acknowledgment changed')
        with regular(root/'server.private.log').open('rb') as stream:
            stream.seek(actual['log_offset']);text=stream.read(256*1024).decode(errors='replace')
        if step['expected_response'] not in text:errors.append('original console acknowledgment absent')
    runtime=json.loads(regular(root/'runtime.json').read_text())
    expected=runtime['settings_observer']['target_sha256']
    if any(row.get('sha256')!=expected.get(row.get('kind')) for row in rows if row.get('event')=='applied'):errors.append('instrumented bytes mismatch frozen candidate')
    if platform=='folia':
        from check_regions import check as regions,handshakes,load_rows
        regions_rows=load_rows(root/'evidence/region-events.jsonl');ticks=load_rows(root/'evidence/tick-events.jsonl')
        joins={row['connection_id']:row for row in regions_rows if row.get('event')=='join'}
        outcome=regions(regions_rows,ticks,handshakes(root,joins),include_samples=True)
        errors+=outcome['errors']
        try:
            start=rows[receipt['phases']['off']]['time_ns'];end=rows[receipt['phases']['serving']]['time_ns']
            from check_regions import overlap
            if not overlap([r for r in outcome['region_samples'] if start<=r['start_ns']<r['end_ns']<=end]):errors.append('distinct actual owning regions did not overlap during reload/drain window')
        except (KeyError,IndexError):errors.append('region reload window absent')
    return {'status':'failed' if errors else 'passed','errors':errors,'test_count':4 if platform=='folia' else 3,
            'observer_sha256':hashlib.sha256((root/'evidence/settings-events.jsonl').read_bytes()).hexdigest()}
