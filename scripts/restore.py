#!/usr/bin/env python3
"""Restore exact UTF-8 project sources from FULL_PROJECT.txt into a new directory."""
import argparse,hashlib,pathlib,re
p=argparse.ArgumentParser();p.add_argument('bundle');p.add_argument('destination');a=p.parse_args();root=pathlib.Path(a.destination).resolve();root.mkdir(parents=True,exist_ok=True)
with open(a.bundle,'rb') as f:
    count=0
    while header:=f.readline():
        m=re.fullmatch(rb'===== FILE (.+) BYTES (\d+) SHA256 ([a-f0-9]{64}) =====\n',header)
        if not m:continue
        name=m[1].decode('utf-8');target=(root/name).resolve()
        if not target.is_relative_to(root):raise ValueError('Unsafe bundle path')
        content=f.read(int(m[2]));assert hashlib.sha256(content).hexdigest().encode()==m[3],name
        if target.exists():raise FileExistsError(target)
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(content);count+=1
print('Restored',count,'files into',root)
