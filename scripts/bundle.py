#!/usr/bin/env python3
"""Rebuild source listing, checksums and ZIP without build caches or user jobs."""
from pathlib import Path
import hashlib,zipfile
root=Path(__file__).resolve().parent.parent
excluded={'target','optimus-data','.git','__pycache__','backups'}
def members():
    return sorted(p for p in root.rglob('*') if p.is_file() and not set(p.relative_to(root).parts)&excluded and p.name not in {'full-project.txt','SHA256SUMS','.env'} and (not p.name.startswith('.env.') or p.name=='.env.example'))
listing=root/'full-project.txt'
with listing.open('wb') as out:
    out.write('OPTIMUS HeatRoute 2.1 — полное содержимое текстовых файлов\nВосстановление: python3 scripts/restore.py full-project.txt destination\nБинарные файлы поставляются в архиве отдельно.\n\n'.encode('utf-8'))
    for p in members():
        if p.suffix.lower() in {'.jar','.gz','.pdf','.docx','.png','.zip'}:continue
        raw=p.read_bytes()
        try:raw.decode('utf-8')
        except UnicodeDecodeError:continue
        rel=p.relative_to(root).as_posix()
        sha=hashlib.sha256(raw).hexdigest()
        out.write(f'===== FILE {rel} BYTES {len(raw)} SHA256 {sha} =====\n'.encode('utf-8'))
        out.write(raw)
        out.write(f'\n===== END FILE {rel} =====\n\n'.encode('utf-8'))
files=members()+[listing]
(root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.relative_to(root).as_posix()+'\n' for p in files),encoding='utf-8')
archive=root.parent/'OPTIMUS-HeatRoute-Final.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(files+[root/'SHA256SUMS']):z.write(p,'OPTIMUS-HeatRoute/'+p.relative_to(root).as_posix())
print(archive);print(archive.stat().st_size,'bytes;',len(files)+1,'files')
