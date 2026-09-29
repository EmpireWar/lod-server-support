"""Mutation controls for YAML reload/wire evidence; no native acceptance claim."""
import json,unittest
from server_control_smoke import check_snapshot,FIELDS,COMPONENTS
from check_conservative_native import verify_data,sha,KEYS,LABELS,DISK,target_values,expectations
from rig_settings import render,values
class Controls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original=values(render({**dict(zip(KEYS,(9,12,6))),'generation.enabled':False,DISK:0,'service.require_permission':True,'lod.distance.by_world':{'fixture-world':17}}))
        cls.baseline={**cls.original,**dict(zip(KEYS,(1,1,1)))}
        cls.target=dict(zip(KEYS,(5,8,4)))
        configs,cls.radii,cls.counts=expectations(cls.original,cls.baseline,cls.target)
        cls.documents=[render(config).encode() if config else b'schema_version: [broken\n' for config in configs]
    def setUp(self):
        self.log=b'old handshake Server session config received (protocol v20, LOD distance: 5 chunks, enabled: true)\n'
        self.receipt={'status':'passed','target':self.target,'phases':[]};self.files={}
        for label,document,radius,count in zip(LABELS,self.documents,self.radii,self.counts):
            snap={k:0 for k in FIELDS};snap.update(schemaVersion=1,serviceAvailable=True,enabled=True,generationEnabled=False,generationConfiguredForRestart=False,lodDistanceChunks=radius,versions={'components':{k:'fixture' for k in COMPONENTS}})
            raw=(document,json.dumps(snap).encode(),b'Generation: false')
            self.files[label]=raw;start=len(self.log)
            self.log+=(f'Server session config received (protocol v20, LOD distance: {radius} chunks, enabled: true)\n'.encode()*count)+b'ordinary client tick\n'
            self.receipt['phases'].append({'label':label,'client_start':start,'client_end':len(self.log),'settled_seconds':3.1,'client_sha256':sha(self.log[start:]),'config_sha256':sha(raw[0]),'snapshot_sha256':sha(raw[1]),'summary_sha256':sha(raw[2])})
    def verify(self):return verify_data(self.receipt,self.original,self.baseline,self.files,self.log,check_snapshot)
    def change_file(self,label,index,change):
        raw=list(self.files[label]);data=values(raw[index].decode()) if index==0 else json.loads(raw[index]);data.update(change)
        raw[index]=render(data).encode() if index==0 else json.dumps(data).encode();self.files[label]=tuple(raw)
        next(r for r in self.receipt['phases'] if r['label']==label)[('config_sha256','snapshot_sha256','summary_sha256')[index]]=sha(raw[index])
    def test_complete_synthetic_evidence(self):self.assertEqual(self.verify()['phases'],7)
    def test_tokens_are_not_executable_values(self):
        with self.assertRaises(ValueError):target_values(dict(zip(KEYS,('@MEASURED_RADIUS@','@MEASURED_GLOBAL@','@MEASURED_PER_PLAYER@'))))
    def test_saved_only_cannot_publish(self):
        self.change_file('saved-only',1,{'lodDistanceChunks':5})
        with self.assertRaisesRegex(ValueError,'effective'):self.verify()
    def test_invalid_reload_cannot_publish(self):
        self.change_file('rejected',1,{'lodDistanceChunks':5})
        with self.assertRaisesRegex(ValueError,'effective'):self.verify()
    def test_scope_restore_preserves_unrelated_later_edit(self):
        self.change_file('scope-restored',0,{DISK:0})
        with self.assertRaisesRegex(ValueError,'persisted'):self.verify()
    def test_numeric_reload_preserves_generation(self):
        self.change_file('unchanged',0,{'generation.enabled':True})
        with self.assertRaisesRegex(ValueError,'persisted'):self.verify()
    def test_old_matching_receipt_does_not_satisfy_reload(self):
        row=self.receipt['phases'][1];row['client_end']=row['client_start'];row['client_sha256']=sha(b'')
        with self.assertRaisesRegex(ValueError,'receipt count'):self.verify()
    def test_late_duplicate_rejected(self):
        self.log+=b'Server session config received (protocol v20, LOD distance: 9 chunks, enabled: true)\n'
        with self.assertRaisesRegex(ValueError,'late unexpected'):self.verify()
    def test_missing_phase_rejected(self):
        self.receipt['phases'].pop(2)
        with self.assertRaisesRegex(ValueError,'complete phase'):self.verify()
    def test_noncontiguous_log_interval_rejected(self):
        self.receipt['phases'][3]['client_start']+=1
        with self.assertRaisesRegex(ValueError,'noncontiguous'):self.verify()
    def test_settle_window_required(self):
        self.receipt['phases'][0]['settled_seconds']=0
        with self.assertRaisesRegex(ValueError,'late-receipt'):self.verify()
if __name__=='__main__':unittest.main()
