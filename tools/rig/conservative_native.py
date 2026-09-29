"""Owned numeric YAML reload scenario; measured target values remain external inputs."""
import hashlib,json,shutil,time
from pathlib import Path
from server_control_smoke import Driver
from check_conservative_native import KEYS,DISK,target_values,verify
from rig_settings import edit
class ConservativeDriver(Driver):
    def __init__(self,root,config,brand='lss'):
        super().__init__(root,config,brand,'numeric-reload')
        runtime=json.loads((self.root/'runtime.json').read_text())
        self.target=target_values(runtime['settings_contract']['numeric_target'])
        self.receipt.update(target=self.target,phases=[],command_root=self.command)
        self.client=self.root/'settings-client.private.log'
    def phase(self,label,changes=None,reload=True,failure=False):
        command_start=len(self.steps)
        if changes:edit(self.config,changes,platform=self.platform)
        if reload:self.reload(failure=failure)
        self.capture(label,self.generation,self.generation)
        shutil.copyfile(self.config,self.output/(label+'.config.yaml'))
        started=time.monotonic()
        while time.monotonic()-started<3:time.sleep(.1)
        end=self.client.stat().st_size
        if end>32*1024*1024 or end<self.client_offset:raise ValueError('client log rotated or exceeds bound')
        with self.client.open('rb') as stream:stream.seek(self.client_offset);segment=stream.read(end-self.client_offset)
        digest=lambda suffix:hashlib.sha256((self.output/(label+suffix)).read_bytes()).hexdigest()
        self.receipt['phases'].append({'label':label,'command_start':command_start,'command_end':len(self.steps),'client_start':self.client_offset,'client_end':end,'client_sha256':hashlib.sha256(segment).hexdigest(),'settled_seconds':time.monotonic()-started,'config_sha256':digest('.config.yaml'),'snapshot_sha256':digest('.json'),'summary_sha256':digest('.txt')})
        self.client_offset=end
    def exercise(self):
        original=self.configured();raw=self.config.read_bytes();self.generation=original['generation.enabled']
        synthetic={KEYS[0]:1 if self.target[KEYS[0]]!=1 else 2,KEYS[1]:1 if self.target[KEYS[1]]!=1 else 2,KEYS[2]:1}
        try:
            self.reload(synthetic);shutil.copyfile(self.config,self.output/'baseline-config.yaml')
            time.sleep(3);self.client_offset=self.client.stat().st_size
            self.phase('saved-only',self.target,reload=False)
            self.phase('applied')
            self.phase('unrelated',{DISK:3 if original[DISK]!=3 else 4})
            self.phase('scope-restored',synthetic)
            self.phase('unchanged')
            self.config.write_bytes(self.config.read_bytes()+b'\ngeneration: [broken\n')
            self.phase('rejected',failure=True)
        finally:self.config.write_bytes(raw)
        self.phase('restored')
        if self.config.read_bytes()!=raw:raise ValueError('cleanup did not restore original bytes')
def run(root,config):
    driver=ConservativeDriver(Path(root),Path(config))
    try:driver.exercise()
    except Exception as error:
        driver.config.write_bytes((driver.output/'original-config.yaml').read_bytes())
        try:driver.reload()
        finally:driver.finish(error)
        raise
    driver.finish();return verify(driver.root,driver.output/'receipt.json'),driver.output/'receipt.json'
