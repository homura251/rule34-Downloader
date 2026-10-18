#!/usr/bin/env python3
"""Read retained originals back through adb after black-box user flows."""
import importlib.util
import hashlib
from pathlib import Path
import subprocess
import sys
import shlex

spec = importlib.util.spec_from_file_location('origin', Path(__file__).with_name('maestro-origin.py'))
origin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(origin)
evidence = Path(sys.argv[1])
verified = []
for artist, count in [('maestro_alpha', 4), ('maestro_pause', 3), ('maestro_restore', 3), ('maestro_retry', 3), ('maestro_process', 3), ('maestro_empty_one', 3), ('maestro_empty_two', 3), ('maestro_network', 3)]:
    root = f'/sdcard/Download/Rule34 Downloader/{artist}'
    names = subprocess.check_output(['adb', 'shell', 'find ' + shlex.quote(root) + ' -type f'], text=True).strip().splitlines()
    assert len(names) == count, f'{artist}: expected {count} unique originals, found {len(names)}'
    for name in names:
        data = subprocess.check_output(['adb', 'exec-out', 'cat ' + shlex.quote(name)])
        post_id = int(Path(name).name.split('_')[0])
        actual = hashlib.md5(data).hexdigest()
        assert actual == origin.HASHES[post_id], f'{artist}/{post_id}: content MD5 differs'
        verified.append(f'{artist}/{post_id}: {len(data)} bytes, MD5 verified')
(evidence/'verified-originals.txt').write_text('\n'.join(verified) + '\n')
print(f'PASS: {len(verified)} retained original files read back with matching MD5 and no duplicates')
