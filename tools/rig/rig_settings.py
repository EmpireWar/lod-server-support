"""Rig access to the product YAML codec and schema."""
import copy
from functools import lru_cache
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.settings.settings_file import codec_identity, create, edit, render, validate, values as _values

@lru_cache(maxsize=128)
def _document_values(document,side,platform,normalized):
    return _values(document,side=side,platform=platform,normalized=normalized)

def values(document,*,side='server',platform='mod',normalized=False):
    # Repeated evidence checks may reuse identical immutable bytes. Paths are read
    # afresh, so edits cannot reuse stale values; callers receive independent maps.
    if isinstance(document,Path):document=document.read_text(encoding='utf-8')
    return copy.deepcopy(_document_values(document,side,platform,normalized))
