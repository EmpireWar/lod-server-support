#!/usr/bin/env python3
"""Bounded private two-client reload exercise. Invoke while the owned supervisor runs."""
import argparse,json,time
from pathlib import Path
from rig import write,regular
from rig_settings import edit,render,values
from server_control_smoke import Driver,exercise
from check_yaml_reload import deposits,BACKFILL_LOW_RATE,BACKFILL_HIGH_RATE

def preflight_numeric_edits(document,changes,platform):
    """Reject clamped fixture assumptions before touching the run's adopted file."""
    numeric={path:value for path,value in changes.items() if type(value) in (int,float)}
    if not numeric:return
    candidate=render(changes,document=document,platform=platform)
    normalized=values(candidate,platform=platform,normalized=True)
    for path,requested in numeric.items():
        if normalized[path]!=requested:
            raise ValueError('native fixture numeric edit would normalize: '+path)

class ReloadDriver(Driver):
    def __init__(self,root,config,platform):
        super().__init__(root,config,'lss','yaml-live-transitions')
        self.rows=[];self.offset=0;self.phases={};self.native_platform=platform;self.control_receipt=None
    def reload(self,changes=None,failure=False):
        if changes:preflight_numeric_edits(self.config.read_text(encoding='utf-8'),changes,self.platform)
        return super().reload(changes,failure)
    def observe(self):
        path=self.root/'evidence/settings-events.jsonl'
        if not path.exists():return
        if path.stat().st_size>32*1024*1024:raise ValueError('observer evidence budget exceeded')
        with path.open('rb') as stream:
            stream.seek(self.offset)
            for raw in stream:
                if not raw.endswith(b'\n'):break
                self.rows.append(json.loads(raw));self.offset+=len(raw)
        if any(row.get('event')=='failure' for row in self.rows):raise ValueError('native observer failed')
    def wait(self,label,predicate,after=-1,timeout=45):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            self.observe()
            for index in range(after+1,len(self.rows)):
                if predicate(self.rows[index]):
                    self.phases[label]=index;return self.rows[index]
            time.sleep(.1)
        raise ValueError('positive native premise absent: '+label)
    def raw(self,command,response):
        old=self.command
        try:
            self.command,_,suffix=command.partition(' ')
            return self.send(suffix,response)
        finally:self.command=old
    def phase(self,label):return self.rows[self.phases[label]]
    def sample(self,label,predicate,after=-1,timeout=45):
        return self.wait(label,lambda r:r.get('event')=='sample' and predicate(r),after,timeout)
    def execute(self):
        original=self.config.read_bytes()
        try:
            self.sample('connected',lambda r:r['players']==2 and r['sent']>0,timeout=120)
            # Stage the inert file before waiting for work; CLI startup must not
            # consume the short interval between an active sample and reload.
            edit(self.config,{'generation.enabled':False},platform=self.platform)
            # Place both disposable clients before the observation boundary.
            for subject,x in [('A',0),('B',4096)]:self.raw(f'tp RigSubject{subject} {x} 100 0','Teleported')
            self.observe();at=len(self.rows)-1
            self.sample('initial',lambda r:r['players']==2 and r['sent']>0 and r['enabled'] and r['active']>0,after=at,timeout=120)
            at=self.phases['initial']
            self.reload()
            self.wait('off',lambda r:r.get('event')=='generation_policy' and not r['enabled'] and r['active']>0,at)
            off=self.phase('off')
            drained=self.sample('drained',lambda r:not r['enabled'] and r['active']==0 and r['revision']==off['revision'],self.phases['off'],timeout=90)
            self.sample('quiet',lambda r:not r['enabled'] and r['time_ns']>=drained['time_ns']+2_000_000_000,self.phases['drained'])
            self.reload({'generation.enabled':True})
            self.raw('tp RigSubjectA 512 100 0','Teleported')
            quiet=self.phase('quiet')
            self.sample('resumed',lambda r:r['enabled'] and r['completed']>quiet['completed'],self.phases['quiet'],timeout=90)
            self.sample('serving',lambda r:r['sent']>quiet['sent'] and r['players']==2,self.phases['resumed'])
            if self.native_platform=='fabric':self.backfill()
            self.restart_pending()
        finally:
            self.config.write_bytes(original);self.reload()
        control=Driver(self.root,self.config,'lss','invalid-retry')
        try:exercise(control,'invalid-retry')
        except Exception as error:control.finish(error);raise
        else:control.finish()
        self.control_receipt=str((control.output/'receipt.json').relative_to(self.root))
    def restart_pending(self):
        baseline=self.configured();threads=baseline['storage.disk.reader_threads']
        self.observe();at=len(self.rows)-1
        self.reload({'storage.disk.reader_threads':threads+1})
        pending=self.sample('restart_pending',lambda r:r.get('reader_restart_pending') is True,at)
        self.reload({'generation.concurrency.global':3})
        self.sample('restart_retained',lambda r:r.get('reader_restart_pending') is True and r['revision']>pending['revision'],self.phases['restart_pending'])
        self.reload({'storage.disk.reader_threads':threads,'generation.concurrency.global':2})
        self.sample('restart_reverted',lambda r:r.get('reader_restart_pending') is False,self.phases['restart_retained'])
    def backfill(self):
        self.raw('forceload add 1024 0 1264 240','Marked')
        # Wait for real vanilla generation before saving; the observer confirms owner progress.
        self.observe();start=self.rows[-1]['time_ns']
        self.sample('seed_wait',lambda r:r['time_ns']>=start+15_000_000_000,len(self.rows)-1,timeout=40)
        self.raw('save-all flush','Saved the game')
        self.raw('forceload remove all','Unmarked')
        self.reload({'storage.lod_store.backfill.enabled':True,'storage.lod_store.backfill.columns_per_second':BACKFILL_LOW_RATE})
        self.observe();at=len(self.rows)-1
        low=self.sample('backfill_low',lambda r:r.get('backfill_running') and r['backfill_rate']==BACKFILL_LOW_RATE and deposits(r)>0,at,timeout=60)
        self.sample('backfill_low_end',lambda r:r['time_ns']>=low['time_ns']+3_000_000_000 and deposits(r)>deposits(low),self.phases['backfill_low'])
        self.reload({'storage.lod_store.backfill.columns_per_second':BACKFILL_HIGH_RATE})
        high=self.sample('backfill_high',lambda r:r.get('backfill_rate')==BACKFILL_HIGH_RATE,self.phases['backfill_low_end'])
        self.sample('backfill_high_end',lambda r:r['time_ns']>=high['time_ns']+3_000_000_000 and deposits(r)-deposits(high)>BACKFILL_LOW_RATE*(r['time_ns']-high['time_ns'])/1e9+BACKFILL_LOW_RATE,self.phases['backfill_high'])
        self.reload({'storage.lod_store.backfill.enabled':False})
        stopped=self.sample('backfill_stopped',lambda r:r.get('backfill_enabled') is False and r.get('backfill_running') is False,self.phases['backfill_high_end'])
        self.sample('backfill_quiet',lambda r:r['time_ns']>=stopped['time_ns']+2_000_000_000,self.phases['backfill_stopped'])
        self.reload({'storage.lod_store.backfill.enabled':True})
        self.sample('backfill_resumed',lambda r:r.get('backfill_running') and r.get('backfill_enabled') and r['backfill_worker']!=stopped['backfill_worker'] and deposits(r)>0,self.phases['backfill_quiet'])
    def finish_native(self,error=None):
        self.finish(error)
        receipt={key:self.manifest[key] for key in ('run_id','run_hash','profile_hash','scenario_hash')}
        receipt.update(status='failed' if error else 'passed',platform=self.native_platform,phases=self.phases,commands=self.steps,control_receipt=self.control_receipt)
        if error:receipt['error']=str(error)
        write(self.root/'evidence/yaml-reload.json',receipt)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('run',type=Path);p.add_argument('--config',type=Path,required=True);p.add_argument('--platform',choices=['fabric','paper','folia'],required=True);a=p.parse_args()
    driver=ReloadDriver(a.run,a.config,a.platform)
    try:driver.execute()
    except Exception as failure:driver.finish_native(failure);raise SystemExit(1)
    else:driver.finish_native()
