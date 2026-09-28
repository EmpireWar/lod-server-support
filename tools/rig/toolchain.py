"""Exact maintained runner, imported helper, and legacy ownership source identity."""
import hashlib
import shutil
from pathlib import Path


def snapshot(repo):
    repo=Path(repo)
    paths=[repo/'tools/rig/rig',repo/'scripts/lib/harness-lock.sh',repo/'scripts/lib/owned-process.py']
    for directory in ('tools/rig','tools/compat','tools/settings'):
        paths.extend(path for path in (repo/directory).rglob('*')
                     if path.suffix in ('.py','.java','.sh') and not path.name.startswith('test_')
                     and '__pycache__' not in path.parts)
    # Settings fixtures execute the product codec. Freeze its maintained inputs too,
    # including YAML templates, rather than binding only the Python launcher.
    paths.extend(path for path in (repo/'common/src/main').rglob('*') if path.is_file())
    for name in ('common/build.gradle', 'tools/verify/run-gradle.sh'):
        if (repo/name).exists(): paths.append(repo/name)
    result={}
    for path in sorted(set(paths)):
        if path.is_symlink() or not path.is_file():raise ValueError('runner source absent or symlink')
        result[str(path.relative_to(repo))]=hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def verify(repo,expected):
    if not expected or snapshot(repo)!=expected:
        raise ValueError('maintained runtime tool sources changed; prepare a new run')


def retain(repo,destination,expected):
    verify(repo,expected)
    destination=Path(destination);destination.mkdir(mode=0o700,exist_ok=False)
    for name in expected:
        target=destination/name;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(Path(repo)/name,target)
    verify(destination,expected)


def settings_codec(runtime):
    """Only YAML settings runs execute the prepared product codec."""
    names = list(runtime.get('generated_files', {}))
    names.extend(row.get('target', '') for row in runtime.get('stage_files', []))
    if not any(name.endswith(('.yaml', '.yml')) and '-config.' in name for name in names):
        return None
    from rig_settings import codec_identity
    return codec_identity()


def verify_settings_codec(runtime, expected):
    if settings_codec(runtime) != expected:
        raise ValueError('prepared settings codec bytes changed; prepare a new run')
