#!/usr/bin/env python3
"""Small Java-only observer build; no Minecraft process or Gradle invocation."""
import argparse,hashlib,json,os,subprocess,tempfile,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--java-home',required=True,type=Path);p.add_argument('--asm',required=True,type=Path);p.add_argument('--candidate',required=True,type=Path);p.add_argument('--platform',choices=['fabric','paper'],required=True);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
root=Path(__file__).resolve().parent;a.output.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(dir=a.output) as temporary:
 classes=Path(temporary)
 subprocess.run([str(a.java_home/'bin/javac'),'--release','21','-proc:none','-cp',str(a.asm),'-d',str(classes),*map(str,(root/'src').rglob('*.java'))],check=True)
 for filename,observer in [('lss-rig-settings-agent.jar',False),('lss-rig-settings-observer.jar',True)]:
  with zipfile.ZipFile(a.output/filename,'w') as jar:
   if not observer:jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: dev.vox.lssfixture.settings.SettingsAgent\n\n')
   for file in classes.rglob('*.class'):
    if not observer or file.name.startswith('SettingsRecorder'):jar.write(file,file.relative_to(classes))
   # Knot verifies one ASM resource across its explicit startup classpath. Fabric
   # already supplies ASM there; Paperclip's premain classpath is only paper.jar.
   if not observer and a.platform=='paper':
    with zipfile.ZipFile(a.asm) as dep:
     for name in dep.namelist():
      if name.endswith('.class') and name!='module-info.class':jar.writestr(name,dep.read(name))
# Check actual artifact entries: the observer must remain bootstrap-safe, and each
# launch gets exactly its intended ASM provider rather than a duplicate agent copy.
with zipfile.ZipFile(a.output/'lss-rig-settings-agent.jar') as jar:
 names=jar.namelist();asm_classes=[name for name in names if name.startswith('org/objectweb/asm/')]
 if len(names)!=len(set(names)):raise ValueError('duplicate agent archive entries')
 if a.platform=='fabric' and asm_classes:raise ValueError('Fabric agent must use startup-classpath ASM')
 if a.platform=='paper' and names.count('org/objectweb/asm/ClassReader.class')!=1:raise ValueError('Paper premain requires one bundled ASM provider')
with zipfile.ZipFile(a.output/'lss-rig-settings-observer.jar') as jar:
 if any(not name.startswith('dev/vox/lssfixture/settings/SettingsRecorder') or not name.endswith('.class') for name in jar.namelist()):
  raise ValueError('bootstrap observer contains classes outside its MC-free recorder')
# Parse both actual target classes using the exact ASM the built agent will use,
# and prove mutated candidate/processed bytes fail the guard before any native run.
with tempfile.TemporaryDirectory(dir=a.output) as temporary:
 verify=Path(temporary)
 classpath=str((a.output/'lss-rig-settings-agent.jar').resolve())
 if a.platform=='fabric':classpath+=os.pathsep+str(a.asm.resolve())
 subprocess.run([str(a.java_home/'bin/javac'),'--release','21','-proc:none','-cp',classpath,
                 '-d',str(verify),str(root/'checks/ObserverChecks.java')],check=True)
 subprocess.run([str(a.java_home/'bin/java'),'-cp',str(verify)+os.pathsep+classpath,
                 'dev.vox.lssfixture.settings.ObserverChecks',str(a.candidate),a.platform],check=True)
with zipfile.ZipFile(a.candidate) as jar:
 prefix='dev/vox/lss/'+('paper/Paper' if a.platform=='paper' else 'networking/server/')
 hashes={kind:hashlib.sha256(jar.read(prefix+name+'.class')).hexdigest() for kind,name in [('service','RequestProcessingService'),('generation','ChunkGenerationService')]}
(a.output/'identity.json').write_text(json.dumps({'target_sha256':hashes,'candidate_sha256':hashlib.sha256(a.candidate.read_bytes()).hexdigest()},indent=2)+'\n')
