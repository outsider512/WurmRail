#!/usr/bin/env python3
"""Validate shipped WurmRail art, topology contracts and native format safety.

The release additionally runs ClientModelsSmoke.java against the real installed
client's Resources, ColladaImporter and ColladaPropertiesXml implementations.
This check is deliberately independent of that parser and uses no game code.
"""
from __future__ import annotations
import hashlib
import itertools
import json
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
NS={"c":"http://www.collada.org/2005/11/COLLADASchema"}
COUNT=0

def require(ok,message):
    global COUNT
    if not ok: raise AssertionError(message)
    COUNT+=1

def main():
    pack=ROOT/"assets"/"wurmrail-models.jar"
    manifest=json.loads((ROOT/"assets"/"manifest.json").read_text())
    require(manifest.get('axes')=='X east; Y up; Z south; vehicle forward +Z before moving-item yaw','native asset axes must match the actual Wurm renderer')
    require(manifest.get('native_axis_bake')=='track (x,y,z) -> (x,z,y); vehicle (x,y,z) -> (-x,z,-y); reversed triangle winding','static and moving-item native axis bakes must be explicit')
    require(manifest.get('railhead_y')==.21,'native railhead height uses Y up')
    expected={"model.wurmrail.locomotive","model.wurmrail.cart","model.wurmrail.tankcar","model.wurmrail.logcar","model.wurmrail.bulkhopper","model.wurmrail.steamrockdrill","model.wurmrail.industrialwatertank","model.wurmrail.logbunk","model.wurmrail.steamyarder","model.wurmrail.steamwaterpump"}
    expected.update({"model.wurmrail.platform.pickup","model.wurmrail.platform.dropoff"})
    expected.update("model.wurmrail.alignment."+str(mask) for mask in [0]+[sum(1<<i for i in pair) for pair in itertools.combinations(range(8),2)])
    expected.update(f"model.wurmrail.signal.{direction}.{aspect}" for direction in range(8) for aspect in ["red","green"])
    expected.update(f"model.wurmrail.{kind}.{i}" for kind in ["track","junction","station"] for i in range(256))
    expected.update(f"model.wurmrail.trestledeck.{axis}" for axis in range(4))
    expected.add("model.wurmrail.trestlecurve")
    expected.update(f"model.wurmrail.trestlebent.{height}" for height in [1,2,4,8,16,32])
    require(set(manifest["models"])==expected,"manifest must cover vehicles and all 256 masks for each track kind")
    with zipfile.ZipFile(pack) as z:
        maps={}
        for line in z.read("mappings.txt").decode().splitlines():
            if not line.strip() or line.startswith('#'): continue
            key,path=(v.strip() for v in line.split('=',1))
            require(key not in maps,"duplicate mapping: "+key)
            require(not key.endswith('.'),"client pack mapping must not end in a dot: "+key)
            require(path in z.namelist(),"missing model: "+path)
            maps[key]=path
        require(set(maps)==expected,"all native resource mappings must be present")
        triangles=0; maxdraw=0
        for key,path in maps.items():
            raw=z.read(path); model=ET.fromstring(raw)
            record=manifest["models"][key]
            if key.startswith('model.wurmrail.trestlebent.'):
                height=int(key.rsplit('.',1)[1])
                lo,hi=record['bounds']
                require(abs(lo[1]+height)<.001 and abs(hi[1])<.001,'timber stages meet at their end elevations')
            if key=='model.wurmrail.steamwaterpump':
                lo,hi=record['bounds']
                require(-.001<=lo[1]<=.01 and 2.1<hi[1]<2.21,'the pump has grounded feet and an upright delivery pipe')
                require(all(-1.1<lo[i]<hi[i]<1.1 for i in [0,2]),'the pump fits its compact ground footprint')
                require(record['mounts']=={} and record['triangles']<10000,'the pump is modest static machinery without passenger mounts')
            if key in ['model.wurmrail.logbunk','model.wurmrail.steamyarder']:
                lo,hi=record['bounds']
                require(-.001<=lo[1]<=.01,'forestry machinery feet must sit on the ground')
                require(all(-2<lo[i]<hi[i]<2 for i in [0,2]),'forestry storage and machinery must fit a single ground tile')
                require(2.2<hi[1]<2.4 if key.endswith('logbunk') else 3.1<hi[1]<3.3,'forestry models must stand upright with their expected height')
                require(record['mounts']=={},'placed forestry equipment must not have passenger mounts')
                require(record['triangles']<12000,'forestry equipment must stay a modest scenery draw')
            if key=='model.wurmrail.steamrockdrill':
                lo,hi=record['bounds']
                require(-.001<=lo[1]<=.01 and 1.7<hi[1]<2,'compact drill feet must sit on the ground with normal cave headroom')
                require(all(-2<lo[i]<hi[i]<2 for i in [0,2]),'placed drill must fit inside one ground tile')
                require(record['mounts']=={},'a placed drill must not have passenger mounts')
                require(record['triangles']<12000,'industrial drill must stay a modest static scenery draw')
            if key.startswith('model.wurmrail.junction.'):
                materials={m.get('name') for m in model.findall('c:library_materials/c:material',NS)}
                require('cream' not in materials,'junction switches must not retain the obsolete white sign')
                require(record['bounds'][1][1]<1.10,'junction scenery stops at its mechanical lever rather than a floating post')
            if key.startswith('model.wurmrail.alignment.'):
                mask=int(key.rsplit('.',1)[1])
                require(mask==0 or mask.bit_count()==2,'alignment models join exactly two absolute sides')
                require(.23<=record['bounds'][0][1]<=record['bounds'][1][1]<=.40,'alignment highlight must stay above the native railhead')
            if key.startswith('model.wurmrail.signal.'):
                direction,aspect=key.split('.')[-2:]
                require(int(direction) in range(8) and aspect in ['red','green'],'signals must encode one absolute approach and a supported aspect')
                lo,hi=record['bounds']
                require(-.001<=lo[1]<=.001 and 2.50<hi[1]<2.55,'signal posts must stand upright in native Y-up coordinates')
                require(all(-2.001<=lo[i]<=hi[i]<=2.001 for i in [0,2]),'signal must stay inside its track tile')
                materials={m.get('name') for m in model.findall('c:library_materials/c:material',NS)}
                require(('signalgreen' if aspect=='green' else 'signalred') in materials,'active aspect needs a distinct bright lens')
                require(('signalred' if aspect=='green' else 'signalgreen') not in materials,'only the active aspect may be illuminated')
            require(hashlib.sha256(raw).hexdigest()==record["sha256"],"manifest hash differs for "+key)
            require(model.get("version")=="1.4.1","unsupported COLLADA version: "+key)
            require(model.find("c:asset/c:up_axis",NS).text=="Y_UP","incorrect up axis: "+key)
            for image in model.findall('c:library_images/c:image',NS):
                texture=image.find('c:init_from',NS).text
                require('/' not in texture and '\\' not in texture and ('wurmrail/'+texture) in z.namelist(),"texture must resolve inside this pack: "+key)
            require(model.find('c:asset/c:unit',NS).get('meter')=='1',"models must use metre units: "+key)
            effects={e.get('id') for e in model.findall('c:library_effects/c:effect',NS)}
            materials={m.get('name'):m.find('c:instance_effect',NS).get('url')[1:] for m in model.findall('c:library_materials/c:material',NS)}
            require(all(e in effects for e in materials.values()),"all material effects must resolve: "+key)
            for geom in model.findall('c:library_geometries/c:geometry',NS):
                mesh=geom.find('c:mesh',NS)
                sources={}
                for src in mesh.findall('c:source',NS):
                    arr=src.find('c:float_array',NS)
                    require(int(arr.get('count'))==len(arr.text.split()),"incorrect source length: "+key)
                    if src.get('id') in ['positions','normals']:
                        values=[float(v) for v in arr.text.split()]
                        require(all(math.isfinite(v) for v in values),'non-finite native mesh vector: '+key)
                        sources[src.get('id')]=list(zip(values[::3],values[1::3],values[2::3]))
                positions=sources['positions']; normals=sources['normals']
                bounds=[[min(p[k] for p in positions) for k in range(3)],
                        [max(p[k] for p in positions) for k in range(3)]]
                require(all(abs(a-b)<1e-6 for aa,bb in zip(bounds,record['bounds']) for a,b in zip(aa,bb)),'manifest bounds must use native coordinates: '+key)
                require(all(abs(sum(v*v for v in p)-1)<3e-6 for p in normals),'native normals must remain unit length: '+key)
                ntri=0
                for group in mesh.findall('c:triangles',NS):
                    cnt=int(group.get('count')); verts=cnt*3
                    require(0<verts<=24000,"draw exceeds safe Wurm 16-bit index range: "+key)
                    require(group.get('material') in materials,"unresolved triangle material: "+key)
                    inputs=group.findall('c:input',NS)
                    require({p.get('semantic') for p in inputs}=={'VERTEX','NORMAL','TEXCOORD'},"positions, normals and texture coordinates required: "+key)
                    indices=[int(v) for v in group.find('c:p',NS).text.split()]
                    require(len(indices)==verts*3,"incorrect triangle index count: "+key)
                    # Metadata alone does not rotate a mesh in Wurm. Railhead
                    # vertices themselves must be horizontal in native XZ.
                    if group.get('material')=='rail' and key.startswith(('model.wurmrail.track.','model.wurmrail.junction.','model.wurmrail.station.')):
                        require(all(.179999<=positions[indices[i]][1]<=.210001 for i in range(0,len(indices),3)),
                                'railhead stands upright or left the native Y height band: '+key)
                    # Axis swapping is a reflection. Sampling each draw's
                    # complete triangles catches omitted winding reversal as
                    # well as normals transformed on the wrong axis.
                    for triangle in sorted({0,cnt//2,cnt-1,*range(0,cnt,max(1,cnt//16))}):
                        start=triangle*9
                        pts=[positions[indices[start+i]] for i in [0,3,6]]
                        n=normals[indices[start+1]]
                        a=[pts[1][i]-pts[0][i] for i in range(3)]
                        b=[pts[2][i]-pts[0][i] for i in range(3)]
                        face=(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
                        require(sum(v*w for v,w in zip(face,n))>0,'native winding disagrees with surface normal: '+key)
                    maxdraw=max(maxdraw,verts); ntri+=cnt
                require(ntri==record['triangles'],"incorrect triangle total: "+key); triangles+=ntri
            if key in ['model.wurmrail.locomotive','model.wurmrail.cart','model.wurmrail.tankcar','model.wurmrail.logcar']:
                joints={n.get('id'):n for n in model.findall('.//c:node',NS) if n.get('type')=='JOINT'}
                require(set(joints)=={'Root','NullMount1','NullMount2','NullMount3','NullMount4'},"four seat joints and Root required: "+key)
                for name,p in record['mounts'].items():
                    m=[float(v) for v in joints[name].find('c:matrix',NS).text.split()]
                    require(all(abs(m[i]-v)<1e-7 for i,v in zip([3,7,11],p)),"mount matrix differs from native seat coordinate contract: "+name)
                    authored=record['authored_mounts'][name]
                    server=record['server_mounts'][name]
                    require(p==[-authored[0],authored[2],-authored[1]],'native mount must bake the same axis conversion as the moving-item mesh: '+name)
                    require(server==[authored[1],-authored[0],authored[2]],'server Seat API frame must match the native player joint: '+name)
        props=ET.fromstring(z.read('wurmrail/properties.xml'))
        for name in ['locomotive.dae','cart.dae','tankcar.dae','logcar.dae']:
            cfg=props.find(name+'/modelProperties')
            require(cfg is not None and cfg.find('notStaticModelData') is not None,"mountable vehicle needs dynamic Collada model data: "+name)
            require(cfg.find('canHaveMountItems') is None,"vehicle must not enable horse/boat equipment mesh filtering: "+name)
    # Independently check every two-exit curve, including diagonal seam positions
    # and endpoint tangents shared with the neighbouring four-metre tile.
    dirs=[(0,-1),(1,-1),(1,0),(1,1),(0,1),(-1,1),(-1,0),(-1,-1)]
    for a,b in itertools.combinations(dirs,2):
        def p(t): return tuple(2*a[k]*(1-t)**2+2*b[k]*t*t for k in range(2))
        require(p(0)==tuple(2*v for v in a) and p(1)==tuple(2*v for v in b),'curve seam endpoints')
        require(all(-2.0001<=v<=2.0001 for i in range(101) for v in p(i/100)),'centerline escaped tile')
        length=sum(math.dist(p(i/100),p((i+1)/100)) for i in range(100))
        require(0<length<=6,'invalid track curve length')
    print(f"ASSET VALIDATION PASSED: {len(expected)} native meshes, {triangles:,} triangles, largest draw {maxdraw:,} vertices; {COUNT:,} checks")

if __name__=='__main__':
    try: main()
    except Exception as e:
        print('ASSET VALIDATION FAILED: '+str(e),file=sys.stderr)
        raise
