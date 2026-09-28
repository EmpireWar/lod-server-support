import copy,unittest
from check_yaml_reload import check,bound_platform

class ReloadWitness(unittest.TestCase):
    def evidence(self,fabric=True):
        rows=[{'event':'applied','kind':kind} for kind in ('service','generation')]
        phases={}
        base=dict(event='sample',enabled=True,revision=1,active=1,submitted=2,completed=1,timeouts=0,removed=0,players=2,sent=10,reader_threads=2,reader_restart_pending=False)
        def row(name,**change):
            nonlocal base
            base=dict(base,time_ns=len(rows)*3_000_000_000,**{})
            base.update(change);phases[name]=len(rows);rows.append(copy.deepcopy(base))
        row('initial')
        row('off',event='generation_policy',enabled=False,revision=2)
        row('drained',event='sample',active=0,completed=2,tracked_positions=2,tracked_tickets=0,deferred=0)
        row('quiet')
        row('resumed',enabled=True,revision=3,submitted=3,completed=3)
        row('serving',sent=20)
        if fabric:
            row('backfill_low',backfill_running=True,backfill_enabled=True,backfill_rate=1,backfill_revision=4,backfill_worker=8,backfill_status='running: 1 deposited')
            row('backfill_low_end',backfill_status='running: 4 deposited')
            row('backfill_high',backfill_rate=16,backfill_revision=5)
            row('backfill_high_end',backfill_status='running: 20 deposited')
            row('backfill_stopped',backfill_running=False,backfill_enabled=False,backfill_revision=6)
            row('backfill_quiet')
            row('backfill_resumed',backfill_running=True,backfill_enabled=True,backfill_revision=7,backfill_worker=9,backfill_status='running: 1 deposited')
        row('restart_pending',reader_restart_pending=True)
        row('restart_retained',revision=4)
        row('restart_reverted',reader_restart_pending=False)
        rows.append({'event':'closed','overflow':False})
        return rows,phases
    def test_platform_cannot_skip_frozen_scenario_requirements(self):
        with self.assertRaises(ValueError):bound_platform({'platform':'paper'},{'server_platform':'fabric'})
        with self.assertRaises(ValueError):bound_platform({'platform':'other'},{'server_platform':'other'})
        self.assertEqual('fabric',bound_platform({'platform':'fabric'},{'server_platform':'fabric'}))
    def test_complete_witnesses(self):
        for platform in ('fabric','paper','folia'):
            rows,phases=self.evidence(platform=='fabric');self.assertEqual([],check(rows,phases,platform))
    def test_generation_negative_controls(self):
        for phase,key,value in [('off','active',0),('drained','completed',1),('quiet','submitted',3),('quiet','enabled',True),('resumed','completed',2),('serving','sent',10),('drained','tracked_tickets',1),('drained','deferred',1),('initial','players',1),('restart_pending','reader_threads',3),('restart_retained','reader_restart_pending',False),('restart_reverted','reader_restart_pending',True)]:
            with self.subTest(phase=phase,key=key):
                rows,phases=self.evidence();rows[phases[phase]][key]=value;self.assertTrue(check(rows,phases,'fabric'))
    def test_backfill_negative_controls(self):
        for phase,key,value in [('backfill_low_end','backfill_status','running: 1 deposited'),('backfill_low_end','backfill_status','running: 15 deposited'),('backfill_high','backfill_worker',9),('backfill_high_end','backfill_status','running: 4 deposited'),('backfill_stopped','backfill_running',True),('backfill_quiet','backfill_status','running: 21 deposited'),('backfill_resumed','backfill_worker',8)]:
            with self.subTest(phase=phase,key=key):
                rows,phases=self.evidence();rows[phases[phase]][key]=value;self.assertTrue(check(rows,phases,'fabric'))
    def test_reused_phases_and_unchanged_pacing(self):
        rows,phases=self.evidence();phases['restart_retained']=phases['restart_pending'];self.assertTrue(check(rows,phases,'fabric'))
        rows,phases=self.evidence();rows[phases['backfill_high_end']]['backfill_status']='running: 7 deposited';self.assertTrue(check(rows,phases,'fabric'))
    def test_missing_phase_and_unclosed_writer(self):
        rows,phases=self.evidence();del phases['off'];self.assertTrue(check(rows,phases,'fabric'))
        rows,phases=self.evidence();rows.pop();self.assertTrue(check(rows,phases,'fabric'))
