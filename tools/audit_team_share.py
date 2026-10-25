"""Inspect source candidates without printing matching secret values. Not a proof of absence.

Run before staging/pushing. Git excludes caches and game data via .gitignore.
Only reports path, line and category, never a matched credential.
"""
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
RULES={
    'service_key':re.compile(r'\bsk-[A-Za-z0-9_-]{20,}'),
    'github_token':re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{35,})'),
    'private_key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'aws_access_key':re.compile(r'\bAKIA[A-Z0-9]{16}\b'),
    'credential_url':re.compile(r'https?://[^\s/<>"\']+:[^\s/<>"\']+@'),
}

def audit():
    names=sorted(set(subprocess.check_output(['git','ls-files','-c','-o','--exclude-standard','-z'],cwd=ROOT).decode('utf-8').strip('\0').split('\0')))
    issues=[];total=0
    for name in names:
        if not name: continue
        path=ROOT/name
        if not path.is_file(): continue
        total+=1
        if path.stat().st_size>=50*1024*1024: issues.append({'path':name,'kind':'large_file','line':None})
        if path.suffix.lower() in {'.key','.pem','.p12','.ysm','.pyc'} or any(p in {'saves','runs','.gradle-user-home','__pycache__'} for p in path.relative_to(ROOT).parts):
            issues.append({'path':name,'kind':'private_or_runtime_file','line':None})
        data=path.read_bytes()
        if b'\0' in data[:8192]: continue
        try: text=data.decode('utf-8-sig')
        except UnicodeDecodeError: continue
        for line,value in enumerate(text.splitlines(),1):
            for kind,pattern in RULES.items():
                if pattern.search(value): issues.append({'path':name,'kind':kind,'line':line})
    print(json.dumps({'candidate_files':total,'issues':issues,'note':'Pattern scan only; review staged files and history separately. No credential values are printed.'},ensure_ascii=False,indent=2))
    return bool(issues)

if __name__=='__main__': sys.exit(audit())
