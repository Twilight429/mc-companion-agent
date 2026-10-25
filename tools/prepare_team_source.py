"""Export a new, source-only team snapshot; never copies Git history or game state.

Uses Git's tracked + nonignored file inventory, retains LGPL notices, and replaces
the small restricted upstream UI assets with independently drawn primitives.
No downloads, model calls, credentials or writes to the source checkout.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

ROOT = Path(__file__).resolve().parents[1]
TEXTURE = 'numen-api/common/src/main/resources/assets/numen_api/textures/'
MIT_ICONS = {
    'copy','refresh','edit','delete','flag','menu','back','more','user','users',
    'user_plus','settings','search','cpu','plug','robot','book','persona','volume',
    'shirt','brush','map_pin','backpack','message','reply','pin','read','user_x',
    'moon','sun','folder','folder_plus','check','cancel',
}
EXCLUDE_FILES = {'.github/workflows/build.yml', '.github/workflows/publish.yml',
                 'numen-api/common/src/main/resources/numen_api.png',
                 'numen/common/src/main/resources/numen.png'}

def permitted(name):
    p = Path(name)
    if name in EXCLUDE_FILES or name.startswith('assets/branding/'):
        return False
    if name.startswith('numen-api/tools/ui-textures/') and p.suffix.lower() in {'.png','.jpg','.webp','.svg','.gif'}:
        return False
    if name.startswith(TEXTURE):
        return name == TEXTURE + 'gui/sprites/' + p.name and p.name in {'icon_' + k + '.png' for k in MIT_ICONS}
    return True

def png(width, height, pixels):
    def chunk(kind, data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    raw = b''.join(b'\x00'+bytes(pixels[y*width*4:(y+1)*width*4]) for y in range(height))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')

def primitive(kind):
    # Independently specified geometry; never reads source-image pixels.
    size = 32 if kind == 'cube' else 16
    data = bytearray(size*size*4)
    def dot(x,y,color=(255,255,255,255)):
        if 0 <= x < size and 0 <= y < size:
            start=(y*size+x)*4; data[start:start+4]=bytes(color)
    def line(a,b,color=(255,255,255,255)):
        x,y=a; xx,yy=b; dx=abs(xx-x); dy=-abs(yy-y); sx=1 if x<xx else -1; sy=1 if y<yy else -1; error=dx+dy
        while True:
            dot(x,y,color)
            if (x,y)==(xx,yy): break
            e=2*error
            if e>=dy: error+=dy; x+=sx
            if e<=dx: error+=dx; y+=sy
    if kind == 'cube':
        for y in range(size):
            for x in range(size): dot(x,y,(25,40,53,255))
        for a,b in [((16,4),(28,11)),((28,11),(28,24)),((28,24),(16,30)),((16,30),(4,24)),((4,24),(4,11)),((4,11),(16,4)),((4,11),(16,18)),((16,18),(28,11)),((16,18),(16,30))]: line(a,b,(99,210,183,255))
    elif kind == 'white':
        data=bytearray((255,255,255,255)*size*size)
    elif kind in {'up','down'}:
        mid=5 if kind=='up' else 10; outer=10 if kind=='up' else 5
        for thick in (0,1): line((3,outer+thick),(8,mid+thick));line((8,mid+thick),(13,outer+thick))
    elif kind == 'mic':
        for y in range(2,9):
            for x in range(6,10): dot(x,y)
        for a,b in [((4,6),(4,10)),((4,10),(11,10)),((11,10),(11,6)),((8,10),(8,13)),((5,13),(11,13))]: line(a,b)
    elif kind == 'compact':
        for y in (3,7,11): line((3,y),(12,y));line((3,y+1),(12,y+1))
    return png(size,size,data)

def write_manifest(destination, paths=None):
    paths = paths if paths is not None else [p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()]
    records=[]
    for name in sorted(set(paths)-{'SOURCE_MANIFEST.json'}):
        data=(destination/name).read_bytes()
        if b'\0' not in data: data=data.replace(b'\r\n',b'\n')
        records.append({'path':name,'sha256':hashlib.sha256(data).hexdigest()})
    document={'upstream':'https://github.com/Dwinovo/minecraft-numen','upstream_commit':'daae0f14a00dfa8ab302683e93609cc862bf0798',
              'snapshot_date':'2026-10-25','hash_basis':'SHA256; files without NUL bytes have CRLF normalized to LF','files':records}
    (destination/'SOURCE_MANIFEST.json').write_text(json.dumps(document,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return len(records)

def export(destination):
    destination=destination.resolve()
    if destination.exists(): raise ValueError('Output must be a NEW directory; nothing will be overwritten.')
    raw=subprocess.check_output(['git','ls-files','-c','-o','--exclude-standard','-z'],cwd=ROOT)
    names=sorted(set(raw.decode('utf-8').strip('\0').split('\0')))
    selected=[n for n in names if n and permitted(n)]
    destination.mkdir(parents=True)
    for name in selected:
        source=ROOT/name
        if not source.is_file() or source.is_symlink(): raise ValueError('Expected regular source file: '+name)
        target=destination/name;target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,target)
    for name,kind in {
        TEXTURE+'gui/white.png':'white',TEXTURE+'gui/sprites/chevron_up.png':'up',
        TEXTURE+'gui/sprites/chevron_down.png':'down',TEXTURE+'gui/sprites/icon_mic.png':'mic',
        TEXTURE+'gui/sprites/icon_compact.png':'compact',
        'numen-api/common/src/main/resources/numen_api.png':'cube',
        'numen/common/src/main/resources/numen.png':'cube',
    }.items():
        path=destination/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(primitive(kind))
    (destination/'README.md').write_text((ROOT/'docs/TEAM_START.md').read_text(encoding='utf-8').replace('](TEAM_','](docs/TEAM_').replace('`TEAM_VERIFICATION.md`','[团队快照验证](docs/TEAM_VERIFICATION.md)'),encoding='utf-8')
    # Root README's links to the retained architecture documentation also need docs/.
    readme=destination/'README.md';text=readme.read_text(encoding='utf-8')
    for name in ('architecture-mind-model','input-pipeline','shell','spatial-perception','permission-layer'):
        text=text.replace(']('+name+'.md)','](docs/'+name+'.md)')
    readme.write_text(text,encoding='utf-8')
    english=destination/'README_EN.md'
    english.write_text('> Team research derivative of Numen; the current team guide is [README.md](README.md). Upstream documentation below is retained for reference, not an official release claim.\n\n'+english.read_text(encoding='utf-8'),encoding='utf-8')
    importer=destination/'tools/import_mcblock.py';text=importer.read_text(encoding='utf-8')
    old='Path("D:/MC/.minecraft/versions/SceneAdapt-1.21.1/schematics")'
    if old not in text and 'Path("schematics")' not in text: raise ValueError('Importer default changed; review portable export patch.')
    importer.write_text(text.replace(old,'Path("schematics")').replace('默认 SceneAdapt-1.21.1 实例','默认当前目录下的 schematics；建议显式传入本人实例路径'),encoding='utf-8')
    properties=destination/'gradle.properties';text=properties.read_text(encoding='utf-8')
    properties.write_text(text.replace('mod_name=Numen\n','mod_name=MC Companion Agent\n').replace('mod_author=dwinovo\n','mod_author=dwinovo and team contributors\n'),encoding='utf-8')
    workflow=destination/'.github/workflows/build.yml';workflow.parent.mkdir(parents=True,exist_ok=True)
    workflow.write_text((ROOT/'tools/team-fabric.yml').read_text(encoding='utf-8'),encoding='utf-8')
    total=write_manifest(destination)
    print(json.dumps({'output':str(destination),'files':total,'restricted_upstream_files_omitted':len(names)-len(selected),'git_history_copied':False},ensure_ascii=False))

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    export(parser.parse_args().output)
