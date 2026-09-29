"""Run-owned UI phases. Coordinates must be bound to an actually inspected screenshot.
Never claims visual acceptance; inspect the captures before the existing finalizer.
"""
import argparse,json,time,sys,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('phase',choices=['discover','status','pending','restore','save-failure']);p.add_argument('--layout',type=Path);a=p.parse_args()
r=a.root.resolve();manifest=json.loads((r/'manifest.json').read_text());profile=json.loads((r/'profile.json').read_text())
from rig import read,write,inside
from native_window import find
from private_input import XInput
from rig_settings import values
window,owner=find(r,'instances/lss-rig-client/minecraft');write(r/'game-window.json',dict(window=window));write(r/'game-window-owner.json',owner)
driver=XInput(r,window,owner);e=r/'evidence';game=r/'instances/lss-rig-client/minecraft';config=game/'config/lss-client-config.yaml'
def snap(name):driver.capture(name+'.png')
def copy(name):data=config.read_bytes();(e/(name+'.yaml')).write_bytes(data);return values(data.decode(),side='client')
def check(receive,slow=None):
 deadline=time.monotonic()+5
 while True:
  configured=values(config,side='client')
  if configured['lod.receive']is receive and(slow is None or configured['lod.download.slow_start_on_join']is slow):return configured
  if time.monotonic()>deadline:raise ValueError('real saved config did not converge to expected values')
  time.sleep(.1)
last_action_completed_ms=0
def click(role):
 global last_action_completed_ms
 driver.click(*layout[role])
 if role!='status_export':last_action_completed_ms=time.time_ns()//1_000_000
 time.sleep(.5)
def export(name,receive):
 from ui_snapshot_wait import observe_after_action
 def request(deadline):
  before={x.name:x.stat().st_mtime_ns for x in (game/'lss-diagnostics').glob('*.json')}
  click('status_export')
  while time.monotonic()<deadline:
   changed=[x for x in (game/'lss-diagnostics').glob('*.json')if before.get(x.name)!=x.stat().st_mtime_ns]
   if len(changed)>1:raise ValueError('exactly one fresh typed export required')
   if len(changed)==1:return changed[0].read_bytes()
   time.sleep(.1)
  raise ValueError('exactly one fresh typed export required before deadline')
 def retain(attempt,raw):(e/(name+'-observation-'+str(attempt)+'.json')).write_bytes(raw)
 data,observation=observe_after_action(request,last_action_completed_ms,receive,retain)
 (e/(name+'.json')).write_bytes(data)
 write(e/(name+'-timing.json'),dict(run_hash=manifest['run_hash'],**observation))
 snap(name+'-settled')

def navigate(name):
 for action in layout[name]:
  if action.startswith('key:'):driver.key(action[4:]);time.sleep(.5)
  else:click(action)

def command_status(route,reload=False):
 global last_action_completed_ms
 # Operator supplies the inspected route from this screen back to gameplay.
 navigate(route)
 if reload:
  driver.key('t');driver.text('/lss reload');driver.key('Return')
  last_action_completed_ms=time.time_ns()//1_000_000;time.sleep(1)
 driver.key('t');driver.text('/lss status');driver.key('Return');time.sleep(.5)

try:
 if a.phase=='discover':snap('batch-current-screen');print(json.dumps(dict(window=window,pid=owner['pid'],capture=str(e/'batch-current-screen.png'))));raise SystemExit()
 if not a.layout:raise ValueError('phase requires run-bound inspected layout')
 layout=read(a.layout);assert layout['run_hash']==manifest['run_hash']
 image=inside(e,layout['inspected_screenshot']);assert hashlib.sha256(image.read_bytes()).hexdigest()==layout['inspected_screenshot_sha256'];assert layout['dialect']in('legacy','modern')
 # This is an operator action record, not a human review or automatic pass.
 write(e/'batch-layout.json',layout)
 if a.phase=='status':
  driver.key('t');driver.text('/lss status');driver.key('Return');last_action_completed_ms=time.time_ns()//1_000_000;time.sleep(.8);snap('status-command-readable');export('initial-status-export',True)
 elif a.phase=='pending':
  check(True,True);copy('canonical-restored-baseline')
  click('sodium_slow');click('sodium_reception');click('sodium_status');snap('status-entry-open')
  check(True,True);copy('parent-return-before-apply-config')
  driver.key('Escape');time.sleep(.5);snap('escape-parent-return')
  if layout.get('sodium_general'):click('sodium_general')
  snap('parent-refreshed-draft-preserved');click('sodium_apply');check(False,False);copy('preserved-draft-applied-config')
  click('sodium_status');export('saved-before-reload-export',True)
  command_status('status_to_game',reload=True);export('preserved-draft-applied-export',False)
 elif a.phase=='restore':
  navigate('open_sodium_from_status')
  if layout.get('sodium_general'):click('sodium_general')
  click('sodium_reception');click('sodium_slow');click('sodium_apply');check(True,True)
  click('sodium_status');command_status('status_to_game',reload=True);export('reception-applied-export',True);copy('canonical-restored-baseline')
 elif a.phase=='save-failure':
  baseline=config.read_bytes();check(True,True);copy('save-failure-before')
  navigate('open_sodium_from_status')
  if layout.get('sodium_general'):click('sodium_general')
  click('sodium_reception')
  mode=config.parent.stat().st_mode
  config.parent.chmod(mode & ~0o222)
  try:
   click('sodium_apply');snap('applied-unsaved-readable');assert config.read_bytes()==baseline;copy('save-failure-after')
  finally:config.parent.chmod(mode)
  click('recovery_keep');command_status('sodium_to_game');export('save-failure-export',True)
  navigate('open_sodium_from_status');click('recover_draft');click('recovery_retry');check(False,True);copy('retry-saved-config')
  click('sodium_status');export('retry-before-reload-export',True)
  command_status('status_to_game',reload=True);export('retry-reloaded-export',False)
  config.write_bytes(baseline);command_status('status_to_game',reload=True);check(True,True)
  copy('final-restored-config');snap('final-restored-saved');export('final-restored-export',True)
 print(json.dumps(dict(run=str(r),phase=a.phase,result='raw phase controls passed; screenshots require visual inspection')))
finally:driver.close()
