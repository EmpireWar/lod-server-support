#!/usr/bin/env python3
"""Run-owned console acceptance for explicit YAML reloads and typed exports.
Uses only the existing supervisor command queue; never opens another process's stdin.
Run in a disposable settings smoke attempt, separate from performance/source lanes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import time
import uuid
from rig import alive, inside, regular, write
from rig_settings import edit, values

FIELDS={'schemaVersion','capturedAtMillis','serviceAvailable','enabled','generationEnabled',
        'generationConfiguredForRestart','lodDistanceChunks','uptimeSeconds','sentSections',
        'rawBytes','wireBytes','bandwidthWindowBytesPerSecond','versions'}
COMPONENTS={'LSS','MINECRAFT','LOADER','SODIUM','XAERO','VOXY','CONNECTOR','C2ME'}

def check_snapshot(snapshot,summary,running,configured):
    if set(snapshot)!=FIELDS:raise ValueError('typed export fields differ from allowlist')
    if snapshot['schemaVersion']!=1 or snapshot['serviceAvailable'] is not True or snapshot['enabled'] is not True:
        raise ValueError('active enabled service required')
    if snapshot['generationEnabled'] is not running or snapshot['generationConfiguredForRestart'] is not configured:
        raise ValueError('configured/running generation distinction incorrect')
    if ('(restart pending)' in summary)!=(running!=configured):raise ValueError('restart summary disagrees with typed export')
    versions=snapshot['versions']
    if set(versions)!={'components'} or set(versions['components'])!=COMPONENTS:raise ValueError('version component allowlist changed')
    if any(not isinstance(value,str) or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._+~-]{0,95}',value) for value in versions['components'].values()):
        raise ValueError('unbounded/non-version export metadata')
    for key in FIELDS-{'versions','serviceAvailable','enabled','generationEnabled','generationConfiguredForRestart'}:
        if type(snapshot[key]) is not int or snapshot[key]<0:raise ValueError('invalid numeric snapshot field: '+key)

def changed_keys(before,after):return {key for key in before.keys()|after.keys() if before.get(key)!=after.get(key)}

def expect_config(before,after,allowed):
    extra=changed_keys(before,after)-set(allowed)
    if extra:raise ValueError('reload changed unrelated configured keys: '+','.join(sorted(extra)))

class Driver:
    def __init__(self,root,config,brand,mode):
        self.root=root.resolve(strict=True);self.mode=mode
        self.manifest=json.loads(regular(self.root/'manifest.json').read_text())
        if self.manifest.get('status')!='running' or not alive(json.loads(regular(self.root/'owner.json').read_text())):
            raise ValueError('live owning rig supervisor required')
        runtime=json.loads(regular(self.root/'runtime.json').read_text())
        launch=next((item for item in runtime['launches'] if item['id']=='server'),None)
        if launch is None:raise ValueError('owned server launch required')
        self.server=inside(self.root,launch['cwd'])
        self.config=regular(config.resolve(strict=True))
        if not self.config.is_relative_to(self.server):raise ValueError('config must be the adopted file inside this owned server')
        self.platform='paper' if 'plugins' in self.config.parts else 'mod'
        self.command='lsslod' if brand=='lss' else 'vsslod'
        self.exports=self.server/(brand+'-diagnostics')
        self.queue=inside(self.root,'commands')
        if not self.queue.is_dir():raise ValueError('supervisor command queue not ready')
        self.id='settings-'+uuid.uuid4().hex
        self.output=inside(self.root,'evidence/'+self.id);self.output.mkdir()
        self.steps=[];self.snapshots={};self.blocker=None
        self.receipt={'schema_version':1,'run_id':self.manifest['run_id'],'run_hash':self.manifest['run_hash'],
                      'profile_hash':self.manifest['profile_hash'],'scenario_hash':self.manifest['scenario_hash'],
                      'mode':mode,'config_relative':str(self.config.relative_to(self.root)),
                      'steps':self.steps,'snapshots':self.snapshots,'status':'running'}
        shutil.copyfile(self.config,self.output/'original-config.yaml')
    def configured(self):return values(self.config,platform=self.platform)
    def send(self,suffix,response):
        request_id=self.id+'-'+str(len(self.steps)).zfill(2)
        request={'launch_id':'server','command':self.command+' '+suffix,'timeout_seconds':45,'response_contains':response}
        write(self.queue/(request_id+'.json'),request)
        result_path=self.queue/'results'/(request_id+'.json');deadline=time.monotonic()+47
        while not result_path.exists():
            if time.monotonic()>deadline:raise ValueError('owned command result timeout: '+suffix)
            time.sleep(.1)
        result=json.loads(regular(result_path).read_text())
        step={'command':request['command'],'expected_response':response,'queue_result':result};self.steps.append(step)
        if result.get('status')!='response_observed':raise ValueError('console response not observed: '+suffix)
        with open(regular(self.root/'server.private.log'),'rb') as stream:
            stream.seek(result['log_offset']);text=stream.read(256*1024).decode(errors='replace')
        matching=[line for line in text.splitlines() if response in line]
        if not matching:raise ValueError('acknowledged command response missing from owned log')
        step['response_lines']=matching[:4]
        print(suffix+': observed',flush=True)
        return text
    def capture(self,label,running,configured):
        before=set(self.exports.glob('diagnostics-*.json')) if self.exports.exists() else set()
        self.send('diagnostics export','Diagnostics exported: ')
        fresh=set(self.exports.glob('diagnostics-*.json'))-before
        if len(fresh)!=1:raise ValueError('one fresh export pair required')
        path=regular(fresh.pop());summary_path=regular(path.with_suffix('.txt'))
        if max(path.stat().st_size,summary_path.stat().st_size)>65536:raise ValueError('export exceeds64KiB')
        snapshot=json.loads(path.read_text());summary=summary_path.read_text()
        check_snapshot(snapshot,summary,running,configured)
        shutil.copyfile(path,self.output/(label+'.json'));shutil.copyfile(summary_path,self.output/(label+'.txt'))
        self.snapshots[label]={'json_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'summary_sha256':hashlib.sha256(summary_path.read_bytes()).hexdigest(),'running':running,'configured':configured}
        return snapshot
    def reload(self, changes=None, failure=False):
        if changes:edit(self.config,changes,platform=self.platform)
        return self.send('reload','Reload failed:' if failure else 'Reloaded '+self.config.name+':')
    def finish(self,error=None):
        if self.blocker is not None:
            try:self.blocker.rmdir();self.blocker=None
            except OSError as cleanup_error:
                self.receipt["blocker_cleanup_error"]=str(cleanup_error)
                error=error or cleanup_error
        self.receipt['status']='failed' if error else 'passed'
        if error:self.receipt['error']=str(error)
        shutil.copyfile(self.config,self.output/'final-config.yaml')
        self.receipt['final_config_sha256']=hashlib.sha256(self.config.read_bytes()).hexdigest()
        write(self.output/'receipt.json',self.receipt)
        print(str(self.output/'receipt.json'),flush=True)


def exercise(driver,mode,previous=None):
    original=driver.config.read_bytes();baseline=driver.configured()
    generation=baseline['generation.enabled'];distance=baseline['lod.distance.default_chunks']
    shutil.copyfile(driver.config,driver.output/'baseline-config.yaml')
    driver.capture('initial',generation,generation)
    try:
        edit(driver.config,{'generation.enabled':not generation},platform=driver.platform)
        driver.capture('saved-only',generation,generation)
        driver.reload()
        driver.capture('applied',not generation,not generation)
        before=driver.config.read_bytes()
        driver.reload()
        driver.capture('unchanged',not generation,not generation)
        if driver.config.read_bytes()!=before:raise ValueError('no-op reload rewrote disk')
        if mode=='invalid-retry':
            # Intentionally malformed operator edit; the product must reject it atomically.
            driver.config.write_bytes(before+b'\ngeneration: [broken\n')
            driver.reload(failure=True)
            driver.capture('rejected',not generation,not generation)
            driver.config.write_bytes(before)
            driver.reload()
            driver.capture('retried',not generation,not generation)
    finally:
        driver.config.write_bytes(original)
        driver.reload()
    restored=driver.capture('restored',generation,generation)
    if restored['lodDistanceChunks']!=distance:raise ValueError('restored effective distance differs')
    expect_config(baseline,driver.configured(),set())

def verify_receipt(root,path):
    root=root.resolve(strict=True);path=regular(path.resolve(strict=True))
    if not path.is_relative_to(root/'evidence'):raise ValueError('receipt must belong to this run evidence')
    manifest=json.loads(regular(root/'manifest.json').read_text());receipt=json.loads(path.read_text())
    for key in ('run_id','run_hash','profile_hash','scenario_hash'):
        if receipt.get(key)!=manifest.get(key):raise ValueError('receipt identity mismatch: '+key)
    if receipt.get('status')!='passed':raise ValueError('attempt receipt did not pass')
    baseline=values(regular(path.parent/'baseline-config.yaml'),platform='paper' if 'plugins' in Path(receipt['config_relative']).parts else 'mod')
    generation=baseline['generation.enabled'];mode=receipt['mode']
    expected={'initial':(generation,generation),'saved-only':(generation,generation),
              'applied':(not generation,not generation),'unchanged':(not generation,not generation),
              'restored':(generation,generation)}
    if mode=='invalid-retry':expected.update(rejected=(not generation,not generation),retried=(not generation,not generation))
    elif mode!='reload-restore':raise ValueError('unknown receipt mode')
    if set(receipt['snapshots'])!=set(expected):raise ValueError('required snapshot phase absent')
    for label,(running,configured) in expected.items():
        snapshot_path=regular(path.parent/(label+'.json'));summary_path=regular(path.parent/(label+'.txt'))
        claim=receipt['snapshots'][label]
        if hashlib.sha256(snapshot_path.read_bytes()).hexdigest()!=claim['json_sha256'] or hashlib.sha256(summary_path.read_bytes()).hexdigest()!=claim['summary_sha256']:
            raise ValueError('captured export bytes changed: '+label)
        check_snapshot(json.loads(snapshot_path.read_text()),summary_path.read_text(),running,configured)
    if not receipt['steps']:raise ValueError('required console steps absent')
    command_root=receipt['steps'][0]['command'].split(' ')[0]
    if command_root not in ('lsslod','vsslod'):raise ValueError('invalid server command root')
    suffixes=['diagnostics export','diagnostics export','reload','diagnostics export','reload','diagnostics export']
    if mode=='invalid-retry':suffixes+=['reload','diagnostics export','reload','diagnostics export']
    suffixes+=['reload','diagnostics export']
    if [step['command'] for step in receipt['steps']]!=[command_root+' '+suffix for suffix in suffixes]:
        raise ValueError('required console command sequence differs')
    for step in receipt['steps']:
        result=step['queue_result'];actual=json.loads(regular(root/'commands/results'/(result['request_id']+'.json')).read_text())
        if actual!=result or actual.get('status')!='response_observed':raise ValueError('owned command evidence changed')
        with open(regular(root/'server.private.log'),'rb') as stream:
            stream.seek(actual['log_offset']);output=stream.read(256*1024).decode(errors='replace')
        if step['expected_response'] not in output:raise ValueError('console response absent from original log')
    final=regular(path.parent/'final-config.yaml')
    if hashlib.sha256(final.read_bytes()).hexdigest()!=receipt['final_config_sha256']:raise ValueError('final config capture changed')
    expect_config(baseline,values(final,platform='paper' if 'plugins' in Path(receipt['config_relative']).parts else 'mod'),set())
    return {'status':'passed','mode':mode,'run_id':manifest['run_id'],'run_hash':manifest['run_hash']}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--config',type=Path);p.add_argument('--verify-receipt',type=Path)
    p.add_argument('--brand',choices=['lss','vss'],default='lss');p.add_argument('--mode',choices=['reload-restore','invalid-retry']);p.add_argument('--previous-receipt',type=Path)
    args=p.parse_args()
    if args.verify_receipt:
        print(json.dumps(verify_receipt(args.run,args.verify_receipt)));raise SystemExit(0)
    if args.config is None or args.mode is None:p.error('--config and --mode are required for live execution')
    driver=Driver(args.run,args.config,args.brand,args.mode)
    try:exercise(driver,args.mode,args.previous_receipt)
    except Exception as error:driver.finish(error);raise SystemExit(1)
    else:driver.finish()
