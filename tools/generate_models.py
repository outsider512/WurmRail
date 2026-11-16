#!/usr/bin/env python3
"""Build original WurmRail game meshes. Standard library only; no game art copied.

Authoring coordinates: metres, +X east, -Y north/vehicle front, +Z up. Native
COLLADA output explicitly bakes tracks (x,y,z) -> (x,z,y) and moving vehicles
(x,y,z) -> (-x,z,-y), with reversed winding. The Wurm renderer uses Y up and
ignores COLLADA up_axis; moving items add 180 degrees to their client yaw.
A track tile is
four metres wide. Direction mask bits are N NE E SE S SW W NW. Connected
direction pairs use the same quadratic Bezier as RailGeometry: 2*A, 0, 2*B.
The models are native COLLADA 1.4.1, supported by the installed Wurm client.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import itertools
import json
import math
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets" / "serverpack"
NS = "http://www.collada.org/2005/11/COLLADASchema"
ET.register_namespace("", NS)
DIRECTIONS = [(0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1)]
MATERIALS = {
    "iron": ((.105, .125, .145), .34, 38),
    "steel": ((.52, .57, .59), .62, 68),
    "rail": ((.42, .47, .49), .58, 76),
    "railweb": ((.20, .235, .25), .38, 42),
    "green": ((.10, .255, .225), .25, 30),
    "greenlight": ((.17, .34, .29), .28, 32),
    "red": ((.46, .12, .09), .20, 22),
    "brass": ((.69, .47, .20), .62, 72),
    "wood": ((.32, .205, .115), .06, 6),
    "woodlight": ((.43, .29, .16), .08, 8),
    "wooddark": ((.24, .155, .09), .04, 5),
    "coal": ((.065, .07, .075), .18, 16),
    "lamp": ((.98, .79, .41), .35, 40),
    "cream": ((.84, .80, .66), .10, 10),
    "ballast": ((.26, .28, .27), .03, 4),
    "signalred": ((.98, .025, .015), .20, 28),
    "signalgreen": ((.035, .95, .15), .20, 28),
}

def vecadd(a, b): return tuple(a[i] + b[i] for i in range(3))
def vecsub(a, b): return tuple(a[i] - b[i] for i in range(3))
def mul(a, k): return tuple(v * k for v in a)
def dot(a, b): return sum(a[i] * b[i] for i in range(3))
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def unit(a):
    d = math.sqrt(dot(a, a))
    return mul(a, 1 / d) if d > 1e-10 else (0., 0., 1.)

class Mesh:
    def __init__(self):
        self.faces = defaultdict(list)
        self.joints = {}

    def tri(self, a, b, c, mat):
        if dot(cross(vecsub(b, a), vecsub(c, a)), cross(vecsub(b, a), vecsub(c, a))) > 1e-18:
            self.faces[mat].append((tuple(a), tuple(b), tuple(c)))

    def quad(self, a, b, c, d, mat):
        self.tri(a, b, c, mat); self.tri(a, c, d, mat)

    def box(self, center, size, mat, angle=0):
        cx, cy, cz = center; sx, sy, sz = (v/2 for v in size)
        co, si = math.cos(angle), math.sin(angle)
        verts = [(cx+x*co-y*si, cy+x*si+y*co, cz+z)
                 for x,y,z in [(-sx,-sy,-sz),(sx,-sy,-sz),(sx,sy,-sz),(-sx,sy,-sz),
                               (-sx,-sy,sz),(sx,-sy,sz),(sx,sy,sz),(-sx,sy,sz)]]
        for ids in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]:
            self.quad(*(verts[i] for i in ids), mat)

    def cylinder(self, a, b, radius, mat, sides=16, radius2=None, cap=True):
        d=unit(vecsub(b,a)); ref=(0,0,1) if abs(d[2])<.9 else (0,1,0)
        u=unit(cross(d,ref)); v=cross(d,u); radius2=radius if radius2 is None else radius2
        aa=[]; bb=[]
        for i in range(sides):
            t=math.tau*i/sides; n=vecadd(mul(u,math.cos(t)),mul(v,math.sin(t)))
            aa.append(vecadd(a,mul(n,radius))); bb.append(vecadd(b,mul(n,radius2)))
        for i in range(sides):
            j=(i+1)%sides
            self.quad(aa[i],aa[j],bb[j],bb[i],mat)
            if cap:
                self.tri(a,aa[j],aa[i],mat); self.tri(b,bb[i],bb[j],mat)

    def beam(self, a, b, width, depth, mat):
        d=vecsub(b,a); l=math.hypot(d[0],d[1])
        self.box(mul(vecadd(a,b),.5),(width,l,depth),mat,math.atan2(-d[0],d[1]))

    def merge(self, other, offset=(0,0,0)):
        for mat, fs in other.faces.items():
            self.faces[mat].extend(tuple(vecadd(v,offset) for v in f) for f in fs)

    def bounds(self):
        pts=[v for fs in self.faces.values() for f in fs for v in f]
        return [[min(v[k] for v in pts) for k in range(3)], [max(v[k] for v in pts) for k in range(3)]]

def log_bunk():
    """Original empty timber rack. Real stored logs remain in its native inventory."""
    m=Mesh()
    # Open sides and raised sleepers make this visibly a timber bunk, not a bin.
    for x in [-1.42,1.42]:
        m.box((x,0,.15),(.34,3.64,.30),"wooddark")
        for y in [-1.48,1.48]:
            m.box((x,y,.045),(.58,.52,.09),"iron")
    for y in [-1.43,0,1.43]:
        m.box((0,y,.35),(3.58,.28,.20),"wood")
        for x in [-1.55,1.55]:
            m.box((x,y,.36),(.40,.34,.23),"iron")
            # Tall square stakes are open above the stack, with steel caps.
            m.box((x,y,1.28),(.13,.16,1.94),"iron")
            m.box((x,y,2.25),(.20,.23,.10),"steel")
            m.cylinder((x,y-.105,.44),(x,y+.105,.44),.047,"brass",8)
            side=1 if x>0 else -1
            m.cylinder((x-side*.40,y,.42),(x,y,1.07),.045,"steel",8)
    # Low end stops retain short timber without covering the open side loading.
    for y in [-1.57,1.57]:
        m.box((0,y,.64),(2.96,.16,.17),"woodlight")
        m.box((0,y,.94),(2.96,.16,.13),"wood")
        for x in [-1.24,1.24]:
            m.cylinder((x,y-.105,.64),(x,y+.105,.64),.04,"iron",8)
    # Small industrial identity plate is attached to the crossbeam, not hovering.
    m.box((0,-1.672,.63),(.62,.025,.17),"green")
    for x in [-.25,.25]:m.cylinder((x,-1.69,.63),(x,-1.70,.63),.025,"brass",8)
    return m

def steam_water_pump():
    """Original compact piston pump on a grounded skid; operating needs remain future work."""
    m=Mesh()
    for x in [-.75,.75]:
        m.box((x,0,.10),(.25,1.90,.20),"wooddark")
        for y in [-.76,.76]:m.box((x,y,.045),(.36,.35,.09),"iron")
    for y in [-.70,.60]:m.box((0,y,.24),(1.80,.18,.15),"iron")
    m.box((0,0,.34),(1.65,1.47,.16),"green")
    # Horizontal pump body, piston and exposed low-speed flywheel.
    m.cylinder((-.32,-.16,.80),(.62,-.16,.80),.28,"greenlight",20)
    for x in [-.34,.64]:m.cylinder((x-.035,-.16,.80),(x+.035,-.16,.80),.32,"brass",20)
    m.cylinder((-.74,-.16,.80),(-.31,-.16,.80),.075,"steel",12)
    m.cylinder((-.86,-.16,.80),(-.76,-.16,.80),.49,"iron",24)
    m.cylinder((-.88,-.16,.80),(-.74,-.16,.80),.14,"brass",16)
    for angle in range(0,360,60):
        a=math.radians(angle)
        m.cylinder((-.85,-.16,.80),(-.85,-.16+.44*math.cos(a),.80+.44*math.sin(a)),.025,"steel",8)
    for x in [-.18,.49]:m.box((x,-.16,.51),(.23,.54,.32),"green")
    # Intake and raised delivery pipe identify the water-producing machine.
    m.cylinder((.63,.49,.35),(.63,.49,1.92),.105,"green",16)
    for z in [.40,.84,1.52,1.88]:m.cylinder((.63,.49,z-.035),(.63,.49,z+.035),.155,"brass",16)
    m.cylinder((.63,.49,1.92),(.63,.27,2.04),.105,"green",16)
    m.cylinder((.63,.27,2.04),(.63,-.53,2.04),.105,"green",16)
    m.cylinder((.63,-.53,2.04),(.63,-.64,2.04),.16,"steel",16)
    m.cylinder((.63,.49,.74),(.63,-.16,.80),.10,"green",16)
    m.cylinder((.24,-.16,.80),(.24,-.16,1.26),.035,"brass",10)
    m.cylinder((.24,-.16,1.24),(.24,-.16,1.29),.22,"red",20)
    for angle in range(0,360,90):
        a=math.radians(angle)
        m.cylinder((.24,-.16,1.265),(.24+.20*math.cos(a),-.16+.20*math.sin(a),1.265),.018,"brass",8)
    m.cylinder((-.18,.20,1.01),(-.18,.34,1.23),.025,"brass",10)
    m.cylinder((-.18,.34,1.23),(-.18,.40,1.23),.12,"brass",18)
    m.cylinder((-.18,.40,1.23),(-.18,.415,1.23),.10,"cream",18)
    m.cylinder((-.18,.42,1.23),(-.13,.42,1.28),.007,"iron",6)
    m.box((0,-.72,.48),(.54,.035,.15),"brass")
    return m

def steam_yarder():
    """Original compact steam-style winch with a short mast, drums and cable sheave."""
    m=Mesh()
    for x in [-1.40,1.40]:
        m.box((x,0,.14),(.32,3.56,.28),"wooddark")
        for y in [-1.48,1.48]:m.box((x,y,.045),(.55,.48,.09),"iron")
    for y in [-1.36,0,1.36]:m.box((0,y,.33),(3.20,.19,.18),"iron")
    m.box((0,.22,.46),(2.82,2.54,.13),"green")
    # Paired cable drums and integral motor remain within the single-tile skid.
    m.cylinder((-1.36,-.49,.94),(1.36,-.49,.94),.085,"steel",12)
    for x in [-.61,.61]:
        m.cylinder((x-.36,-.49,.94),(x+.36,-.49,.94),.35,"iron",20)
        for edge in [-.40,.40]:m.cylinder((x+edge-.04,-.49,.94),(x+edge+.04,-.49,.94),.46,"green",20)
        for offset in [-.25,-.15,-.05,.05,.15,.25]:
            m.cylinder((x+offset-.025,-.49,.94),(x+offset+.025,-.49,.94),.366,"steel",20)
    for x in [-1.23,1.23]:
        m.box((x,-.49,.68),(.22,.58,.42),"greenlight")
        m.cylinder((x-.14,-.49,.94),(x+.14,-.49,.94),.15,"brass",16)
    # Small vertical boiler gives the agreed dark-steel/brass steam styling.
    m.cylinder((-.73,.93,.54),(-.73,.93,1.81),.40,"green",20)
    for z in [.60,1.21,1.76]:m.cylinder((-.73,.93,z-.035),(-.73,.93,z+.035),.424,"brass",20)
    m.cylinder((-.73,.93,1.80),(-.73,.93,1.94),.40,"iron",20,radius2=.17)
    m.cylinder((-.73,.93,1.92),(-.73,.93,2.17),.10,"iron",12)
    m.cylinder((-.73,.93,2.13),(-.73,.93,2.19),.15,"steel",12)
    m.cylinder((-.73,.58,1.56),(-.73,.43,1.56),.125,"brass",16)
    m.cylinder((-.73,.43,1.56),(-.73,.415,1.56),.103,"cream",16)
    m.cylinder((-.73,.409,1.56),(-.69,.409,1.615),.01,"iron",6)
    m.box((.72,1.02,.70),(.87,.68,.32),"green")
    m.cylinder((.28,.85,.91),(1.16,.85,.91),.17,"iron",16)
    m.cylinder((.76,1.03,.84),(.76,1.03,1.25),.032,"brass",8)
    m.cylinder((.76,1.03,1.22),(.76,1.03,1.27),.15,"red",16)
    # A-frame mast with a close overhead sheave. No cable crosses the woodlot.
    for x in [-1.11,1.11]:
        m.cylinder((x,.08,.49),(x*.28,-.16,2.99),.09,"iron",12)
        m.cylinder((x,1.39,.49),(x*.28,-.16,2.99),.058,"steel",10)
        m.box((x,.08,.54),(.34,.42,.13),"greenlight")
        m.cylinder((x,.08,.60),(x,.08,.68),.07,"brass",10)
    m.cylinder((-.42,-.16,2.98),(.42,-.16,2.98),.075,"brass",12)
    m.cylinder((-.09,-.16,2.98),(.09,-.16,2.98),.22,"iron",20)
    for x in [-.10,.10]:m.cylinder((x-.025,-.16,2.98),(x+.025,-.16,2.98),.25,"steel",20)
    m.cylinder((-.61,-.64,1.28),(-.02,-.40,3.02),.021,"steel",8)
    m.cylinder((.02,-.40,3.02),(.61,-.64,1.28),.021,"steel",8)
    # Two short hand levers provide visible manual control fittings.
    for x in [.58,.95]:
        m.cylinder((x,-1.36,.55),(x,-1.52,1.08),.025,"brass",8)
        m.cylinder((x-.09,-1.52,1.08),(x+.09,-1.52,1.08),.04,"red",10)
    return m

def steam_rock_drill():
    """Original compact four-face drill; static scenery sized for a normal cave."""
    m=Mesh()
    for y in [-1.12,1.12]:
        m.box((0,y,.105),(2.80,.29,.21),"wooddark")
    for x in [-1.13,1.13]:
        m.box((x,0,.24),(.17,2.50,.16),"iron")
        for y in [-1.12,1.12]:
            m.box((x,y,.035),(.46,.46,.07),"iron")
            m.cylinder((x,y,.31),(x,y,.35),.065,"brass",8)
    m.box((0,0,.34),(1.54,1.54,.18),"green")
    # Central boiler and its brass fittings establish the steam-machinery style.
    # Fuel/water/pressure are cosmetic only in this first implementation.
    m.cylinder((0,0,.43),(0,0,1.54),.43,"green",20)
    for h in [.48,.98,1.48]:
        m.cylinder((0,0,h-.035),(0,0,h+.035),.457,"brass",20)
    m.cylinder((0,0,1.54),(0,0,1.63),.43,"iron",20,radius2=.28)
    m.cylinder((0,0,1.60),(0,0,1.84),.12,"iron",16)
    m.cylinder((0,0,1.80),(0,0,1.86),.17,"steel",16)
    m.cylinder((.32,-.34,1.42),(.32,-.49,1.42),.13,"brass",16)
    m.cylinder((.32,-.49,1.42),(.32,-.503,1.42),.103,"cream",16)
    m.cylinder((.32,-.509,1.42),(.367,-.509,1.471),.009,"iron",6)
    m.cylinder((-.38,.20,.65),(-.38,.20,1.46),.032,"brass",10)
    m.cylinder((-.38,.20,1.46),(-.38,-.22,1.46),.032,"brass",10)
    m.cylinder((-.38,-.22,1.44),(-.38,-.22,1.56),.02,"steel",8)
    m.cylinder((-.38,-.22,1.54),(-.38,-.22,1.58),.115,"red",12)
    # Four fixed cardinal spindle assemblies, with bevelled cutting heads.
    # No diagonal or invisible extra mining reach is suggested by the artwork.
    for direction in range(4):
        angle=direction*math.pi/2; dx,dy=math.sin(angle),-math.cos(angle)
        tx,ty=-dy,dx
        def p(radius,z):return (dx*radius,dy*radius,z)
        m.box(p(.98,.61),(.30,1.02,.18),"iron",angle)
        m.cylinder(p(.48,.96),p(1.21,.96),.16,"green",12)
        for radius in [.60,1.12]:m.cylinder(p(radius-.045,.96),p(radius+.045,.96),.192,"brass",12)
        m.cylinder(p(1.20,.96),p(1.70,.96),.083,"steel",12)
        m.cylinder(p(1.62,.96),p(1.91,.96),.155,"iron",12,radius2=.018)
        for cut in range(3):
            r=1.63+cut*.075
            m.cylinder(p(r,.96),p(r+.025,.96),.152-cut*.032,"steel",12,radius2=.13-cut*.032)
        for side in [-1,1]:
            m.cylinder((dx*.61+tx*.22*side,dy*.61+ty*.22*side,.48),
                       (dx*1.16+tx*.22*side,dy*1.16+ty*.22*side,.83),.032,"steel",8)
        m.cylinder(p(.99,.52),p(.99,.72),.045,"iron",8)
    return m

def industrial_water_tank():
    """Original upright industrial water vessel, with static filling/consumer outlets."""
    m=Mesh()
    for x in [-1.10,1.10]:
        for y in [-1.10,1.10]:
            m.box((x,y,.065),(.50,.50,.13),"iron")
            m.box((x,y,.39),(.18,.18,.66),"steel")
            m.cylinder((x,y,.73),(x,y,.78),.14,"brass",12)
    for y in [-1.10,1.10]:
        m.box((0,y,.27),(2.38,.12,.16),"iron")
        m.cylinder((-1.10,y,.31),(1.10,y,.67),.035,"steel",8)
        m.cylinder((1.10,y,.31),(-1.10,y,.67),.035,"steel",8)
    # Closed riveted shell and shallow tapered heads: a storage tank, not a boiler.
    m.cylinder((0,0,.53),(0,0,.78),1.13,"greenlight",32,radius2=1.58)
    m.cylinder((0,0,.78),(0,0,3.93),1.58,"green",32)
    m.cylinder((0,0,3.93),(0,0,4.20),1.58,"greenlight",32,radius2=.38)
    for h in [.80,1.81,2.85,3.94]:
        m.cylinder((0,0,h-.035),(0,0,h+.035),1.602,"iron",32)
        for index in range(24):
            angle=math.tau*index/24;x,y=math.cos(angle),math.sin(angle)
            m.cylinder((x*1.60,y*1.60,h),(x*1.635,y*1.635,h),.026,"brass",6)
    for index in range(8):
        angle=math.tau*index/8;x,y=math.cos(angle)*1.59,math.sin(angle)*1.59
        m.cylinder((x,y,.87),(x,y,3.87),.018,"steel",6)
    # Top hatch and short breather, kept closed in the static model.
    m.cylinder((0,0,4.17),(0,0,4.30),.36,"iron",24)
    m.cylinder((0,0,4.30),(0,0,4.36),.43,"steel",24)
    m.box((0,0,4.405),(.32,.07,.07),"brass")
    for index in range(10):
        angle=math.tau*index/10
        m.cylinder((math.cos(angle)*.385,math.sin(angle)*.385,4.34),
                   (math.cos(angle)*.385,math.sin(angle)*.385,4.38),.025,"brass",6)
    # Independent fill and consumer pipes, both visually closed by hand valves.
    for x,z in [(-.55,.91),(.55,1.21)]:
        m.cylinder((x,-1.40,z),(x,-1.83,z),.095,"iron",12)
        m.cylinder((x,-1.72,z),(x,-1.72,z+.22),.055,"brass",10)
        m.cylinder((x,-1.83,z),(x,-1.90,z),.13,"steel",12)
        for index in range(12):
            a=math.tau*index/12;b=math.tau*(index+1)/12
            m.cylinder((x+math.cos(a)*.15,-1.72+math.sin(a)*.15,z+.23),
                       (x+math.cos(b)*.15,-1.72+math.sin(b)*.15,z+.23),.018,"red",6)
    # External level scale is a static visual fitting; actual quantity is in Status.
    m.box((1.67,-.42,2.25),(.075,.24,2.33),"iron")
    m.box((1.717,-.42,2.25),(.025,.15,2.18),"cream")
    for h in [1.25,1.65,2.05,2.45,2.85,3.25]:m.box((1.734,-.42,h),(.012,.13,.025),"iron")
    for y in [.18,.59]:m.cylinder((1.69,y,.10),(1.69,y,4.11),.032,"steel",8)
    for h in [.25+i*.30 for i in range(13)]:m.cylinder((1.69,.18,h),(1.69,.59,h),.026,"steel",8)
    return m

def bulk_hopper():
    """Original open industrial storage hopper; static scenery, not a rail car."""
    m=Mesh()
    # Owner-moveable feet and a braced frame keep the discharge above the ground.
    for x in [-1.31,1.31]:
        for y in [-1.31,1.31]:
            m.box((x,y,.055),(.40,.40,.11),"iron")
            m.box((x,y,1.03),(.14,.14,1.98),"steel")
            m.box((x,y,1.98),(.26,.26,.14),"iron")
    for y in [-1.31,1.31]:
        m.box((0,y,.31),(2.70,.105,.13),"iron")
        m.cylinder((-1.24,y,.4),(1.24,y,1.86),.034,"iron",8)
        m.cylinder((1.24,y,.4),(-1.24,y,1.86),.034,"iron",8)
    for x in [-1.31,1.31]:
        m.box((x,0,.31),(.105,2.70,.13),"iron")
        m.cylinder((x,-1.24,.4),(x,1.24,1.86),.034,"iron",8)
    def ring(half,z): return [(-half,-half,z),(half,-half,z),(half,half,z),(-half,half,z)]
    # Double-sided plate geometry gives the empty interior real thickness.
    for low,high,z0,z1 in [(.40,1.48,1.16,2.08),(1.48,1.48,2.08,3.26)]:
        a,b=ring(low,z0),ring(high,z1)
        ia,ib=ring(max(.1,low-.055),z0),ring(high-.055,z1)
        for i in range(4):
            j=(i+1)%4
            m.quad(a[i],a[j],b[j],b[i],"green")
            m.quad(ia[j],ia[i],ib[i],ib[j],"steel")
            m.quad(b[i],b[j],ib[j],ib[i],"steel")
    for y in [-1.49,1.49]:
        for z in [2.08,2.68,3.29]: m.box((0,y,z),(3.08,.10,.11),"iron" if z<3 else "steel")
        for x in [-1.45,0,1.45]:
            m.box((x,y,2.66),(.075,.105,1.24),"steel")
            for z in [2.12,2.68,3.20]:
                m.cylinder((x,y-.065,z),(x,y+.065,z),.028,"brass",8)
    for x in [-1.49,1.49]:
        for z in [2.08,2.68,3.29]: m.box((x,0,z),(.10,3.08,.11),"iron" if z<3 else "steel")
        for y in [-1.45,0,1.45]: m.box((x,y,2.66),(.105,.075,1.24),"steel")
    # A closed outlet and mechanical gate are static presentation only.
    m.box((0,0,1.08),(.82,.82,.16),"iron")
    m.box((0,0,.94),(.72,.72,.12),"steel")
    m.box((.02,-.10,.86),(.86,.84,.055),"iron")
    m.cylinder((-.20,-.47,1.05),(.20,-.47,1.05),.065,"steel",12)
    m.cylinder((.18,-.47,1.05),(.58,-.47,1.39),.035,"brass",10)
    m.cylinder((.56,-.56,1.38),(.56,-.38,1.38),.07,"red",12)
    return m

def chassis(m, length, engine=False):
    m.box((0,0,.69),(1.5,length-.4,.23),"iron")
    for x in [-.72,.72]: m.box((x,0,.72),(.13,length-.25,.21),"red")
    for y in [-length/2+.16,length/2-.16]:
        m.box((0,y,.76),(1.85,.20,.38),"red")
        m.cylinder((0,y,.7),(0,y+(-.39 if y<0 else .39),.7),.065,"steel",10)
        m.cylinder((-.12,y+(-.39 if y<0 else .39),.7),(.12,y+(-.39 if y<0 else .39),.7),.10,"iron",12)
        for x in [-.63,.63]:
            m.cylinder((x,y,.78),(x,y+(-.18 if y<0 else .18),.78),.105,"iron",12)
    for y in ([-1.28,-.37,.54] if engine else [-1.12,1.12]):
        m.cylinder((-.72,y,.48),(.72,y,.48),.075,"steel",12)
        for x in [-.61,.61]:
            sign=1 if x>0 else -1
            m.cylinder((x-.08,y,.48),(x+.08,y,.48),.275,"iron",20)
            m.cylinder((x+sign*.08,y,.48),(x+sign*.102,y,.48),.285,"steel",20)
            m.cylinder((x+sign*.104,y,.48),(x+sign*.117,y,.48),.21,"red",20)
            m.cylinder((x+sign*.117,y,.48),(x+sign*.137,y,.48),.075,"brass",12)
            for k in range(6):
                t=math.tau*k/6
                # Raised radial spokes remain visible against the dark wheel disc.
                m.cylinder((x+sign*.12,y,.48),(x+sign*.12,y+math.cos(t)*.185,.48+math.sin(t)*.185),.017,"iron",5)
            m.box((x,y,.78),(.25,.48,.07),"iron")
    if engine:
        for x in [-.78,.78]:
            m.box((x,-.38,.47),(.055,1.91,.062),"steel")
            for y in [-1.28,-.37,.54]:
                m.cylinder((x-.025,y,.47),(x+.025,y,.47),.055,"brass",10)

def locomotive():
    m=Mesh(); chassis(m,4.4,True)
    # Saddle tank boiler, curved front face, brass bands and chimney.
    m.cylinder((0,-1.64,1.3),(0,.44,1.3),.47,"green",24)
    m.cylinder((0,-1.68,1.3),(0,-1.62,1.3),.48,"iron",24)
    m.cylinder((0,-1.72,1.3),(0,-1.67,1.3),.355,"iron",24)
    for y in [-1.32,-.54,.24]: m.cylinder((0,y-.027,1.3),(0,y+.027,1.3),.478,"brass",24)
    m.cylinder((0,-1.18,1.7),(0,-1.18,2.21),.115,"iron",16,radius2=.145)
    m.cylinder((0,-1.18,2.21),(0,-1.18,2.31),.185,"iron",16)
    m.cylinder((0,-.25,1.65),(0,-.25,1.97),.16,"brass",16,radius2=.12)
    m.cylinder((0,-1.73,1.3),(0,-1.79,1.3),.07,"brass",12)
    for x in [-.49,.49]:
        m.cylinder((x,-1.45,1.55),(x,.32,1.55),.025,"brass",8)
        for y in [-1.38,.25]: m.cylinder((x,y,1.43),(x,y,1.55),.019,"brass",6)
    # Front headlamp and cowcatcher.
    m.box((0,-1.76,1.98),(.34,.22,.32),"iron")
    m.cylinder((0,-1.89,1.98),(0,-1.92,1.98),.125,"brass",16)
    m.cylinder((0,-1.923,1.98),(0,-1.927,1.98),.10,"lamp",16)
    for x in [-.65,-.43,-.21,0,.21,.43,.65]:
        m.cylinder((x,-1.94,.75),(x*.87,-2.13,.28),.025,"iron",6)
    m.box((0,-2.13,.29),(1.48,.08,.08),"iron")
    # Open-sided cab with two rows of seats, working footplate, detailed controls.
    m.box((0,1.10,.88),(1.72,1.85,.13),"wooddark")
    m.box((0,.28,1.31),(1.64,.11,.76),"green")
    for x in [-.79,.79]:
        m.box((x,1.12,1.12),(.13,1.72,.37),"green")
        for y in [.31,1.94]: m.box((x,y,1.89),(.09,.09,1.45),"green")
        m.box((x,1.17,1.88),(.045,1.43,.045),"brass")
    m.box((0,1.11,2.65),(1.98,2.07,.12),"iron")
    m.box((0,1.11,2.58),(1.85,1.95,.045),"woodlight")
    m.box((0,.31,2.40),(1.64,.09,.28),"green")
    m.box((0,1.94,1.22),(1.65,.10,.59),"green")
    for x in [-.48,.48]:
        for y in [.65/.78,1.25/.78]:
            m.box((x,y,1.025),(.56,.50,.10),"woodlight")
            m.box((x,y+.23,1.245),(.56,.055,.40),"wood")
            m.box((x,y,.925),(.10,.10,.20),"iron")
    for x in [-.29,.0,.29]:
        m.cylinder((x,.205,1.55),(x,.17,1.55),.09,"brass",14)
        m.cylinder((x,.165,1.55),(x,.159,1.55),.071,"cream",14)
        m.cylinder((x,.15,1.55),(x+.026,.15,1.59),.004,"iron",4)
    m.cylinder((.40,.52,.95),(.40,.52,1.45),.025,"brass",8)
    m.cylinder((.30,.52,1.46),(.50,.52,1.46),.025,"red",8)
    for y in [.80,1.55]:
        for x in [-.90,.90]:
            m.box((x,y,.65),(.23,.43,.07),"steel")
    # Coal bunker shelf behind rear seats, with individually faceted coal pieces.
    m.box((0,1.95,1.64),(1.20,.20,.16),"iron")
    for i in range(8):
        x=-.51+i*.145
        m.cylinder((x,1.96,1.70),(x+.02,1.95,1.77+(i%3)*.016),.09,"coal",5,radius2=.045)
    # Fit the existing four-metre consist spacing including the couplers.
    m.faces={mat:[tuple((x,y*.78,z+.025) for x,y,z in f) for f in fs] for mat,fs in m.faces.items()}
    # These locate the character root, rather than the seat cushion. Keep the
    # rider/camera below the cab roof and match WurmRailWorld's native Seat API.
    m.joints={"NullMount1":(-.48,.65,.55),"NullMount2":(.48,.65,.55),
              "NullMount3":(-.48,1.25,.55),"NullMount4":(.48,1.25,.55)}
    return m

def cart():
    m=Mesh(); chassis(m,3.9)
    # Low open goods wagon with warm individual plank boards and steel furniture.
    for i in range(9):
        m.box((-.72+i*.18,0,.81),(.173,3.35,.10),"woodlight" if i%3 else "wood")
    for z in [1.04,1.21,1.38]:
        for x in [-.83,.83]: m.box((x,0,z),(.085,3.37,.15),"woodlight" if int(z*100)%2 else "wood")
        for y in [-1.67,1.67]: m.box((0,y,z),(1.7,.08,.15),"wood")
    for x in [-.89,.89]:
        for y in [-1.65,0,1.65]:
            m.box((x,y,1.19),(.045,.09,.66),"iron")
            for z in [1.02,1.35]: m.cylinder((x-.025,y,z),(x+.025,y,z),.024,"steel",6)
    # Four usable corner seats leave the centre clear for freight.
    for x in [-.55,.55]:
        for y in [-.70/.87,.70/.87]:
            m.box((x,y,.925),(.48,.50,.08),"woodlight")
            for yy in [y-.15,y+.15]: m.box((x,yy,.855),(.08,.06,.14),"iron")
    # Hinges, latches, wheel handbrake and side ladders.
    for y in [-1.72,1.72]:
        for x in [-.52,.52]:
            m.box((x,y,1.02),(.18,.04,.11),"iron")
        m.box((.66,y,1.43),(.10,.05,.12),"steel")
    m.cylinder((.78,1.50,.82),(.78,1.50,1.75),.025,"iron",8)
    for i in range(12):
        t=math.tau*i/12; tt=math.tau*(i+1)/12
        m.cylinder((.78+math.cos(t)*.17,1.50+math.sin(t)*.17,1.75),
                   (.78+math.cos(tt)*.17,1.50+math.sin(tt)*.17,1.75),.019,"iron",6)
    for x in [-.94,.94]:
        for z in [.39,.65]: m.box((x,0,z),(.20,.40,.055),"steel")
    m.faces={mat:[tuple((x,y*.87,z+.025) for x,y,z in f) for f in fs] for mat,fs in m.faces.items()}
    m.joints={"NullMount1":(-.55,-.70,.95),"NullMount2":(.55,-.70,.95),
              "NullMount3":(-.55,.70,.95),"NullMount4":(.55,.70,.95)}
    return m

def tank_car():
    """Original liquid wagon with four passenger sockets on its end platforms."""
    m=Mesh();chassis(m,3.9)
    for y in [-1.66,1.66]:
        for i in range(6):m.box((-.675+i*.27,y,.835),(.25,.80,.10),"woodlight" if i%2 else "wood")
    # Riveted cylindrical vessel with tapered end caps and two steel saddles.
    for y in [-.88,.88]:
        m.box((0,y,.96),(1.22,.19,.26),"iron")
        m.cylinder((0,y-.07,1.48),(0,y+.07,1.48),.666,"iron",24)
    m.cylinder((0,-1.17,1.48),(0,1.17,1.48),.635,"green",24)
    m.cylinder((0,-1.17,1.48),(0,-1.34,1.48),.635,"greenlight",24,radius2=.15)
    m.cylinder((0,1.17,1.48),(0,1.34,1.48),.635,"greenlight",24,radius2=.15)
    for y in [-1.06,0,1.06]:
        m.cylinder((0,y-.025,1.48),(0,y+.025,1.48),.648,"brass",24)
    for y in [-1.05,-.52,0,.52,1.05]:
        for angle in [math.pi*.20,math.pi*.55,math.pi*.90,math.pi*1.30,math.pi*1.65]:
            x,z=math.cos(angle)*.648,1.48+math.sin(angle)*.648
            m.cylinder((x*.985,y,z-.008),(x*1.018,y,z+.008),.022,"steel",8)
    # Top filling hatch, bolted lid, valve and a side access ladder.
    m.cylinder((0,0,2.05),(0,0,2.22),.20,"iron",20)
    m.cylinder((0,0,2.22),(0,0,2.28),.27,"steel",20)
    m.box((0,0,2.32),(.26,.05,.055),"brass")
    for i in range(8):
        angle=math.tau*i/8;x,y=math.cos(angle)*.225,math.sin(angle)*.225
        m.cylinder((x,y,2.25),(x,y,2.29),.022,"brass",6)
    for y in [-.26,.26]:m.cylinder((.79,y,.65),(.79,y,2.22),.025,"steel",8)
    for z in [.77,1.02,1.27,1.52,1.77,2.02]:m.cylinder((.79,-.26,z),(.79,.26,z),.022,"steel",8)
    m.cylinder((0,.82,.83),(0,1.45,.83),.07,"iron",12)
    m.cylinder((0,1.45,.83),(0,1.45,1.05),.05,"brass",10)
    for i in range(12):
        a=math.tau*i/12;b=math.tau*(i+1)/12
        m.cylinder((math.cos(a)*.14,1.45+math.sin(a)*.14,1.06),
                   (math.cos(b)*.14,1.45+math.sin(b)*.14,1.06),.017,"red",6)
    # End seats remain entirely beyond the vessel's ±1.17m authored ends.
    # Scale the chassis longitudinally to keep native couplers inside 4m spacing.
    for x in [-.55,.55]:
        for y in [-1.45/.87,1.45/.87]:
            m.box((x,y,.925),(.44,.40,.08),"woodlight")
            m.box((x,y,.835),(.08,.08,.14),"iron")
    for y in [-1.84,1.84]:
        for x in [-.82,.82]:m.cylinder((x,y,.88),(x,y,1.44),.022,"steel",8)
        m.cylinder((-.82,y,1.44),(.82,y,1.44),.022,"brass",8)
    m.faces={mat:[tuple((x,y*.87,z+.025) for x,y,z in f) for f in fs] for mat,fs in m.faces.items()}
    m.joints={"NullMount1":(-.55,-1.45,.95),"NullMount2":(.55,-1.45,.95),
              "NullMount3":(-.55,1.45,.95),"NullMount4":(.55,1.45,.95)}
    return m

def log_car():
    """Open raw-timber wagon; end platforms keep riders clear of the load bed."""
    m=Mesh();chassis(m,3.9)
    for i in range(9):
        m.box((-.72+i*.18,0,.81),(.173,3.35,.10),"woodlight" if i%3 else "wood")
    # Three stout transverse bolsters and upright stakes hold round timber.
    # Keep the end platforms empty, without decorative logs that imply cargo.
    for y in [-1.13,0,1.13]:
        m.box((0,y,.93),(1.76,.16,.14),"iron")
        for x in [-.86,.86]:
            m.box((x,y,1.61),(.09,.12,1.42),"green")
            m.box((x,y,2.33),(.12,.15,.055),"steel")
            m.box((x,y,1.01),(.18,.25,.19),"iron")
            for z in [1.05,1.19]:m.cylinder((x-.055,y,z),(x+.055,y,z),.024,"brass",8)
            m.beam((x,y,.98),(x*.72,y,1.23),.055,.055,"iron")
    # Chains and anchor rings are folded against the bolsters on an empty car.
    for y in [-1.10,1.10]:
        for x in [-.71,.71]:
            m.cylinder((x,y,.94),(x,y,1.02),.045,"steel",8)
            for at in range(6):
                m.box((x+(at-2.5)*.065,y,1.035),(.045,.055,.025),"steel")
    # Native passenger seat API and authored NullMount roots match the Tank Car.
    for x in [-.55,.55]:
        for y in [-1.45/.87,1.45/.87]:
            m.box((x,y,.925),(.44,.40,.08),"woodlight")
            m.box((x,y,.835),(.08,.08,.14),"iron")
    for y in [-1.84,1.84]:
        for x in [-.82,.82]:m.cylinder((x,y,.88),(x,y,1.44),.022,"steel",8)
        m.cylinder((-.82,y,1.44),(.82,y,1.44),.022,"brass",8)
    for x in [-.94,.94]:
        for y in [-1.70,1.70]:
            for z in [.39,.65]:m.box((x,y,z),(.20,.35,.055),"steel")
    m.faces={mat:[tuple((x,y*.87,z+.025) for x,y,z in f) for f in fs] for mat,fs in m.faces.items()}
    m.joints={"NullMount1":(-.55,-1.45,.95),"NullMount2":(.55,-1.45,.95),
              "NullMount3":(-.55,1.45,.95),"NullMount4":(.55,1.45,.95)}
    return m

def passenger_platform(pickup=True):
    m=Mesh()
    # A thin full-tile boarding pad; players stay on the native ground level.
    m.box((0,0,.025),(3.78,3.78,.05),"wooddark")
    for index in range(12):
        m.box((-1.705+index*.31,0,.055),(.295,3.62,.035),"woodlight" if index%3 else "wood")
    color="greenlight" if pickup else "red"
    for x in [-1.82,1.82]: m.box((x,0,.085),(.10,3.68,.035),color)
    for y in [-1.82,1.82]: m.box((0,y,.085),(3.68,.10,.035),color)
    m.box((1.45,1.45,.68),(.075,.075,1.30),"iron")
    m.box((1.45,1.45,1.31),(.64,.12,.56),color)
    direction=1 if pickup else -1
    for y in [1.375,1.525]:
        m.cylinder((1.45,y,1.13),(1.45,y,1.49),.028,"cream",6)
        for x in [-1,1]:
            m.cylinder((1.45+x*.12,y,1.31+direction*.06),(1.45,y,1.31+direction*.18),.028,"cream",6)
    return m

def bezier(a,b,t):
    return (2*a[0]*(1-t)**2+2*b[0]*t*t,2*a[1]*(1-t)**2+2*b[1]*t*t,0)

def path_points(a,b):
    # 32 samples keep a smooth railhead even on the tightest 45-degree turn.
    return [bezier(a,b,i/32) for i in range(33)]

def sweep(m,points,offset,width,z,height,mat):
    edges=[]
    for i,p in enumerate(points):
        d=unit(vecsub(points[min(i+1,len(points)-1)],points[max(0,i-1)]))
        n=(-d[1],d[0],0)
        edges.append([vecadd(p,vecadd(mul(n,offset+s*width/2),(0,0,zz)))
                      for s,zz in [(-1,z), (1,z), (1,z+height), (-1,z+height)]])
    for a,b in zip(edges,edges[1:]):
        for j in range(4): m.quad(a[j],b[j],b[(j+1)%4],a[(j+1)%4],mat)
    m.quad(*reversed(edges[0]),mat); m.quad(*edges[-1],mat)

def junction_alignment(mask):
    m=Mesh()
    sides=[i for i in range(8) if mask&(1<<i)]
    if mask==0:
        # A red X means no through route; never imply an arbitrary default exit.
        for sign in [-1,1]:
            m.cylinder((-.38,sign*.38,.29),(.38,-sign*.38,.29),.055,"red",8)
        return m
    if len(sides)!=2: raise ValueError("A junction alignment must join exactly two sides")
    points=path_points(DIRECTIONS[sides[0]],DIRECTIONS[sides[1]])
    # The active two-way route sits above the existing .21 m railhead.
    sweep(m,points,0,.12,.26,.025,"lamp")
    for index,outward in [(2,-1),(30,1)]:
        p=points[index]; tangent=unit(vecsub(points[index+1],points[index-1])); direction=mul(tangent,outward)
        normal=(-direction[1],direction[0],0)
        tip=(p[0],p[1],.30)
        for side in [-1,1]:
            wing=vecadd(vecsub(tip,mul(direction,.27)),mul(normal,side*.15))
            m.cylinder(wing,tip,.03,"cream",8)
    return m

def railway_signal(approach, green):
    """A two-lens post facing an absolute incoming side, baked in static world axes.

    The post stands beside the incoming rail, never on the curve centreline.
    Upper red and lower green lenses plus cross/arrow silhouettes distinguish
    aspects without relying only on color or rotating native ground items.
    """
    if approach not in range(8): raise ValueError("Invalid signal approach direction")
    m=Mesh(); d=unit((*DIRECTIONS[approach],0)); right=(-d[1],d[0],0)
    anchor=vecadd(mul((*DIRECTIONS[approach],0),.8),mul(right,1.25))
    def p(x,y,z): return vecadd(anchor,vecadd(mul(right,x),vecadd(mul(d,y),(0,0,z))))
    angle=math.atan2(right[1],right[0])
    m.box(p(0,0,.055),(.34,.34,.11),"iron",angle)
    m.cylinder(p(0,0,.10),p(0,0,2.10),.065,"steel",12)
    for z in [.40,.72,1.04]: m.box(p(0,0,z),(.14,.14,.10),"cream",angle)
    m.box(p(0,0,1.98),(.60,.14,1.06),"iron",angle)
    # The board and lens backs are opaque, so the reverse direction sees a dark back.
    for z,lit in [(2.23,not green),(1.74,green)]:
        m.cylinder(p(0,.075,z),p(0,.12,z),.225,"coal",20)
        mat=("signalgreen" if green else "signalred") if lit else "iron"
        m.cylinder(p(0,.125,z),p(0,.16,z),.18,mat,20)
    if green:
        for start,end in [((0,1.62),(0,1.86)),((-.10,1.76),(0,1.86)),((.10,1.76),(0,1.86))]:
            m.cylinder(p(start[0],.171,start[1]),p(end[0],.171,end[1]),.018,"cream",6)
    else:
        for side in [-1,1]: m.cylinder(p(-.095,.171,2.23+side*.095),p(.095,.171,2.23-side*.095),.019,"cream",6)
    # A small ground arrow points into the controlled incoming side, below the post.
    # This remains absolute and helps identify the signal's front from overhead.
    tip=p(0,.39,.14)
    for side in [-1,1]: m.cylinder(p(side*.14,.15,.14),tip,.025,"cream",6)
    return m

def add_rail_path(m,points,sleepers=True):
    for off in [-.55,.55]:
        sweep(m,points,off,.17,.11,.025,"railweb")
        sweep(m,points,off,.055,.135,.045,"railweb")
        sweep(m,points,off,.105,.18,.03,"rail")
    if sleepers:
        length=sum(math.dist(a,b) for a,b in zip(points,points[1:]))
        count=max(2,round(length/.51))
        for i in range(count):
            f=(i+.5)/count*(len(points)-1); j=min(int(f),len(points)-2)
            p=vecadd(mul(points[j],1-(f-j)),mul(points[j+1],f-j))
            d=unit(vecsub(points[j+1],points[j])); angle=math.atan2(-d[0],d[1])
            m.box((p[0],p[1],.065),(1.65,.20,.10),"wood" if i%3 else "wooddark",angle)
            for off in [-.55,.55]:
                n=(-d[1],d[0],0); q=vecadd(p,mul(n,off))
                m.box((q[0],q[1],.115),(.23,.25,.025),"iron",angle)
    return m

def bufferstop(m,d=(0,-1)):
    # Buffer frame across the terminal, facing the one connected exit.
    angle=math.atan2(d[0],-d[1]); co,si=math.cos(angle),math.sin(angle)
    length=math.hypot(*d)
    # Simulation stops the vehicle centre at the terminal node. Keep the frame
    # beyond its body/coupler, in the unconnected half of this terminal tile.
    shift=(-d[0]/length*1.8,-d[1]/length*1.8)
    def p(x,y,z): return (x*co-y*si+shift[0],x*si+y*co+shift[1],z)
    for x in [-.66,.66]:
        m.cylinder(p(x,-.10,.17),p(x,.22,.83),.065,"red",8)
        m.cylinder(p(x,.88,.14),p(x,.22,.83),.065,"iron",8)
    m.box(p(0,.20,.79),(1.70,.20,.25),"red",angle)
    for x in [-.56,.56]: m.cylinder(p(x,.10,.79),p(x,-.04,.79),.115,"iron",12)

def track(mask):
    m=Mesh(); ds=[DIRECTIONS[i] for i in range(8) if mask&(1<<i)]
    if not ds:
        add_rail_path(m,[(0,-2+i*.125,0) for i in range(33)])
        bufferstop(m)
    elif len(ds)==1:
        d=ds[0]; add_rail_path(m,[(2*d[0]*(1-2*i/32),2*d[1]*(1-2*i/32),0) for i in range(33)])
        bufferstop(m,d)
    else:
        for a,b in itertools.combinations(ds,2): add_rail_path(m,path_points(a,b))
    return m


def trestle_deck(axis):
    length = 4.0 if axis % 2 == 0 else 4.0 * math.sqrt(2)
    dx, dy = DIRECTIONS[axis]
    angle = math.atan2(-dx, dy)
    mesh = Mesh()
    for offset in [-.57, .57]:
        mesh.box((offset * math.cos(angle), offset * math.sin(angle), -.26),
                 (.28, length, .37), "wooddark", angle)
    mesh.box((0, 0, -.065), (1.92, length, .13), "wood", angle)
    add_rail_path(mesh, [(-2 * dx, -2 * dy, 0), (2 * dx, 2 * dy, 0)])
    return mesh


def trestle_curve():
    """Subdivided deck in local coordinates for the client's surveyed-curve deformation."""
    mesh = Mesh()
    points = [(i / 4 - 2, 0, 0) for i in range(17)]
    for offset in [-.57, .57]:
        sweep(mesh, points, offset, .28, -.445, .37, "wooddark")
    sweep(mesh, points, 0, 1.92, -.13, .13, "wood")
    add_rail_path(mesh, points, sleepers=False)
    # The deck itself is the rail bed. Plates subdivided with the deck remain
    # clean even where the route only crosses a small corner of the tile.
    for x in [-1.625, -.625, .375, 1.375]:
        mesh.box((x, 0, .0525), (.25, 1.65, .105), "wooddark")
    return mesh


def trestle_bent(height):
    """A square-ended braced timber stage, with its origin at the top."""
    mesh = Mesh()
    for x in [-1.04, 0, 1.04]:
        mesh.box((x, 0, -height / 2), (.29, .32, height), "wooddark")
    mesh.box((0, 0, -.14), (2.57, .52, .28), "wood")
    levels = math.ceil(height / 4)
    for level in range(levels):
        top = -.16 - level * (height - .24) / levels
        bottom = -.16 - (level + 1) * (height - .24) / levels
        mesh.box((0, 0, bottom), (2.38, .37, .16), "wood")
        mesh.beam((-1.04, -.23, top), (1.04, -.23, bottom), .16, .16, "wood")
        mesh.beam((1.04, .23, top), (-1.04, .23, bottom), .16, .16, "wood")
    return mesh

def _switch_detail(m):
    # The mechanical lever is sufficient scenery. The former white board and
    # its disconnected post floated over the handle without conveying state;
    # the separate ground overlay already displays the live two-way alignment.
    m.box((1.49,1.45,.09),(.45,.49,.15),"wooddark")
    m.cylinder((1.49,1.45,.15),(1.49,1.45,.39),.09,"iron",12)
    m.cylinder((1.49,1.45,.39),(1.70,1.45,.90),.027,"steel",8)
    m.cylinder((1.64,1.45,.75),(1.76,1.45,1.05),.08,"red",12)

def _station_detail(m):
    # Compact platform is rotated into a corner with no diagonal exit.
    m.box((-1.65,1.62,.16),(.66,.72,.30),"wooddark")
    for y in [1.35,1.56,1.77,1.98]: m.box((-1.65,y,.33),(.71,.18,.055),"woodlight")
    for x in [-1.93,-1.38]: m.box((x,1.93,1.01),(.055,.055,1.38),"iron")
    m.box((-1.65,1.93,1.64),(.88,.09,.33),"green")
    m.box((-1.65,1.877,1.64),(.72,.012,.21),"cream")
    for x in [-1.87,-1.43]: m.box((x,1.86,1.64),(.035,.018,.14),"iron")
    m.box((-1.65,1.86,1.64),(.035,.018,.14),"iron")

def place_detail(m,builder,mask,preferred):
    corners=[preferred]+[i for i in [1,3,5,7] if i!=preferred]
    free=next((i for i in corners if not mask&(1<<i)),None)
    # A fully connected diagonal crossing has no safe platform/lever sector.
    # Its track geometry still conveys all routes; never put scenery on rails.
    if free is None: return
    detail=Mesh(); builder(detail)
    angle=((free-preferred)%8)*math.pi/4; co,si=math.cos(angle),math.sin(angle)
    detail.faces={mat:[tuple((x*co-y*si,x*si+y*co,z) for x,y,z in f) for f in fs] for mat,fs in detail.faces.items()}
    m.merge(detail)

def switch_detail(m,mask=0): place_detail(m,_switch_detail,mask,3)
def station_detail(m,mask=0): place_detail(m,_station_detail,mask,5)

def tag(local_name, parent=None, text=None, **attrs):
    e=ET.Element(f"{{{NS}}}{local_name}",attrs) if parent is None else ET.SubElement(parent,f"{{{NS}}}{local_name}",attrs)
    if text is not None: e.text=str(text)
    return e

def floats(xs): return " ".join(f"{v:.6f}".rstrip('0').rstrip('.') if v else "0" for v in xs)

def native_point(p,vehicle=False):
    """Bake Wurm's Y-up mesh basis and its separate moving-item yaw frame.

    This is a reflection, not a rotation. Transform normals the same way and
    reverse triangle winding. Keep the authoring mesh and preview exports in
    their original basis; a COLLADA metadata change alone has no client effect.
    """
    # Ordinary GroundItems use client yaw=-serverHeading. Vehicles arrive as
    # moving creatures and use client yaw=180-serverHeading, requiring an
    # additional half turn in their mesh and native mount positions.
    return (-p[0],p[2],-p[1]) if vehicle else (p[0],p[2],p[1])


def native_bounds(mesh,vehicle=False):
    transformed=[native_point(p,vehicle) for p in mesh.bounds()]
    return [[min(p[k] for p in transformed) for k in range(3)],
            [max(p[k] for p in transformed) for k in range(3)]]


def native_mounts(mesh,vehicle=False):
    return {name: native_point(p,vehicle) for name,p in mesh.joints.items()}


def server_mounts(mesh):
    # Wurm's Seat API uses a different local frame. At zero heading its
    # MovementScheme applies world(deltaX,deltaY)=(-offsetY,offsetX).
    return {name:(p[1],-p[0],p[2]) for name,p in mesh.joints.items()}


def write_dae(mesh,path,vehicle=False):
    root=tag("COLLADA",version="1.4.1")
    asset=tag("asset",root); tag("contributor",asset); tag("created",asset,text="2026-10-15T00:00:00Z")
    tag("modified",asset,text="2026-10-15T00:00:00Z"); tag("unit",asset,name="meter",meter="1"); tag("up_axis",asset,text="Y_UP")
    images=tag("library_images",root)
    for mat in mesh.faces:
        tag('init_from',tag('image',images,id=mat+'_image',name=mat+'_image'),text=mat+'.png')
    effects=tag("library_effects",root); materials=tag("library_materials",root)
    for mat in mesh.faces:
        color,spec,shine=MATERIALS[mat]
        effect=tag("effect",effects,id=mat+"_fx"); profile=tag("profile_COMMON",effect)
        surface=tag('surface',tag('newparam',profile,sid=mat+'_surface'),type='2D')
        tag('init_from',surface,text=mat+'_image')
        sampler=tag('sampler2D',tag('newparam',profile,sid=mat+'_sampler'))
        tag('source',sampler,text=mat+'_surface')
        tag('minfilter',sampler,text='LINEAR'); tag('magfilter',sampler,text='LINEAR')
        technique=tag("technique",profile,sid="common"); phong=tag("phong",technique)
        emission=(.12,.08,.01) if mat=="lamp" else ((.72,.005,.002) if mat=="signalred" else ((.005,.70,.04) if mat=="signalgreen" else (0,0,0)))
        for key,vals in [("emission",emission),
                         ("ambient",mul(color,.38)),("specular",(spec,spec,spec))]:
            tag("color",tag(key,phong),text=floats((*vals,1)))
        # The Wurm renderer ignores phong diffuse colors. A self-contained
        # original solid-color texture is required to preserve the art palette.
        tag('texture',tag('diffuse',phong),texture=mat+'_sampler',texcoord='CHANNEL1')
        tag("float",tag("shininess",phong),text=str(shine))
        tag("instance_effect",tag("material",materials,id=mat,name=mat),url="#"+mat+"_fx")
    geoms=tag("library_geometries",root)
    geometry=tag("geometry",geoms,id="RailMesh",name="RailMesh"); xmlmesh=tag("mesh",geometry)
    # Explicit corner normals preserve the chosen flat shading. Deduplicating
    # source positions and normals greatly reduces XML and client parse cost;
    # Wurm's importer independently expands each draw mesh after reading them.
    vertices=[]; normals=[]; groups=[]; vertex_ids={}; normal_ids={}
    for mat,fs in mesh.faces.items():
        indices=[]
        for face in fs:
            n=tuple(round(v,6) for v in unit(cross(vecsub(face[1],face[0]),vecsub(face[2],face[0]))))
            if n not in normal_ids: normal_ids[n]=len(normals); normals.append(n)
            ni=normal_ids[n]
            for v in face:
                v=tuple(round(p,6) for p in v)
                if v not in vertex_ids: vertex_ids[v]=len(vertices); vertices.append(v)
                indices.append((vertex_ids[v],ni))
        # Client TriangleMesh stores 16-bit indices. Bound every draw mesh to
        # fewer than 32k vertices, even for an eight-way all-routes junction.
        for start in range(0,len(indices),24000): groups.append((mat,indices[start:start+24000]))
    for name,values in [("positions",vertices),("normals",normals)]:
        source=tag("source",xmlmesh,id=name)
        tag("float_array",source,id=name+"_array",count=str(len(values)*3),text=floats(v for p in values for v in native_point(p,vehicle)))
        accessor=tag("accessor",tag("technique_common",source),source="#"+name+"_array",count=str(len(values)),stride="3")
        for k in ["X","Y","Z"]: tag("param",accessor,name=k,type="float")
    source=tag('source',xmlmesh,id='uv')
    # A nondegenerate UV triangle permits finite tangent generation in clients
    # that calculate tangents even without a normal map. Every texture is solid.
    tag('float_array',source,id='uv_array',count='6',text='0.125 0.125 0.875 0.125 0.125 0.875')
    accessor=tag('accessor',tag('technique_common',source),source='#uv_array',count='3',stride='2')
    for k in ['S','T']: tag('param',accessor,name=k,type='float')
    tag("input",tag("vertices",xmlmesh,id="vertices"),semantic="POSITION",source="#positions")
    for mat,indices in groups:
        triangles=tag("triangles",xmlmesh,count=str(len(indices)//3),material=mat)
        tag("input",triangles,semantic="VERTEX",source="#vertices",offset="0")
        tag("input",triangles,semantic="NORMAL",source="#normals",offset="1")
        tag('input',triangles,semantic='TEXCOORD',source='#uv',offset='2',set='0')
        # The native-axis swap has determinant -1. Reverse the three complete
        # corner inputs (including UVs) so outward face normals stay outward.
        tag("p",triangles,text=" ".join(str(i) for start in range(0,len(indices),3)
                                       for corner in (0,2,1) for i in [*indices[start+corner],corner]))
    scenes=tag("library_visual_scenes",root); scene=tag("visual_scene",scenes,id="Scene",name="Scene")
    model=tag("node",scene,id="Model",name="Model",type="NODE")
    instance=tag("instance_geometry",model,url="#RailMesh")
    tc=tag("technique_common",tag("bind_material",instance))
    for mat in mesh.faces: tag("instance_material",tc,symbol=mat,target="#"+mat)
    if mesh.joints:
        jointroot=tag("node",scene,id="Root",sid="Root",name="Root",type="JOINT")
        tag("matrix",jointroot,text="1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1")
        for name,point in mesh.joints.items():
            x,y,z=native_point(point,vehicle)
            j=tag("node",jointroot,id=name,sid=name,name=name,type="JOINT")
            tag("matrix",j,text=f"1 0 0 {x} 0 1 0 {y} 0 0 1 {z} 0 0 0 1")
    tag("instance_visual_scene",tag("scene",root),url="#Scene")
    path.parent.mkdir(parents=True,exist_ok=True)
    ET.ElementTree(root).write(path,encoding="utf-8",xml_declaration=True)

def write_obj(mesh,path):
    lines=["# Original WurmRail model; metres, Z up", "mtllib wurmrail.mtl"]; idx=1
    for mat,fs in mesh.faces.items():
        lines.append("usemtl "+mat)
        for f in fs:
            for v in f: lines.append("v "+floats(v))
            lines.append(f"f {idx} {idx+1} {idx+2}"); idx+=3
    path.write_text("\n".join(lines)+"\n",encoding="utf-8")

def write_glb(mesh,path):
    # Preview glTF's right-handed Y-up basis differs from Wurm's native mesh
    # basis. Keep the existing preview mapping (author x,y,z -> x,z,-y).
    binary=bytearray(); views=[]; accessors=[]; primitives=[]; mats=[]
    def buffer(values,fmt,target):
        while len(binary)%4: binary.append(0)
        start=len(binary); binary.extend(struct.pack('<'+fmt*len(values),*values))
        views.append({"buffer":0,"byteOffset":start,"byteLength":len(binary)-start,"target":target})
        return len(views)-1
    for mat,fs in mesh.faces.items():
        pts=[(v[0],v[2],-v[1]) for f in fs for v in f]
        ns=[unit(cross(vecsub(pts[i+1],pts[i]),vecsub(pts[i+2],pts[i]))) for i in range(0,len(pts),3)]
        posview=buffer([v for p in pts for v in p],"f",34962)
        accessors.append({"bufferView":posview,"componentType":5126,"count":len(pts),"type":"VEC3",
                          "min":[min(p[k] for p in pts) for k in range(3)],"max":[max(p[k] for p in pts) for k in range(3)]})
        pi=len(accessors)-1
        nv=buffer([v for n in ns for _ in range(3) for v in n],"f",34962)
        accessors.append({"bufferView":nv,"componentType":5126,"count":len(pts),"type":"VEC3"})
        color,spec,shine=MATERIALS[mat]
        mats.append({"name":mat,"pbrMetallicRoughness":{"baseColorFactor":[*color,1],"metallicFactor":.65 if mat in ["steel","rail","brass"] else .1,"roughnessFactor":.36 if spec>.4 else .75}})
        primitives.append({"attributes":{"POSITION":pi,"NORMAL":len(accessors)-1},"material":len(mats)-1})
    doc={"asset":{"version":"2.0","generator":"WurmRail original procedural meshes"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":primitives}],"materials":mats,"buffers":[{"byteLength":len(binary)}],"bufferViews":views,"accessors":accessors}
    js=json.dumps(doc,separators=(',',':')).encode(); js+=b' '*((-len(js))%4)
    binary+=b'\0'*((-len(binary))%4)
    blob=struct.pack('<4sII',b'glTF',2,12+8+len(js)+8+len(binary))+struct.pack('<I4s',len(js),b'JSON')+js+struct.pack('<I4s',len(binary),b'BIN\0')+binary
    path.write_bytes(blob)

def write_properties():
    root=ET.Element("properties")
    for name in ["locomotive.dae","cart.dae","tankcar.dae","logcar.dae"]:
        m=ET.SubElement(root,name); props=ET.SubElement(m,"modelProperties")
        # canHaveMountItems enables horse/boat EQUIPMENT mesh selection. Its
        # native update hides meshes not named bodyMesh/saddle/etc. Passenger
        # sockets work independently through NullMount1..4 and dynamic data.
        for flag in ["notStaticModelData","mountNotRotateGround"]: ET.SubElement(props,flag)
        for lod,n in [("lod1",28),("lod2",52),("lod3",80)]: ET.SubElement(props,lod).text=str(n)
        jp=ET.SubElement(m,"jointProperties")
        for i in range(1,5): ET.SubElement(ET.SubElement(jp,"NullMount"+str(i)),"mountrotation").text="0"
    ET.ElementTree(root).write(OUT/"wurmrail"/"properties.xml",encoding="utf-8",xml_declaration=True)

def write_textures():
    # Small original opaque RGB swatches avoid the diffuse-color omission in
    # Wurm's renderer. No image library or external artwork is involved.
    folder=OUT/'wurmrail'; folder.mkdir(parents=True,exist_ok=True)
    def chunk(name,data):
        return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data)&0xffffffff)
    for mat,(color,_,_) in MATERIALS.items():
        rgb=bytes(round(v**.64*255) for v in color)
        pixels=b''.join(b'\0'+rgb*8 for _ in range(8))
        png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',8,8,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')
        (folder/(mat+'.png')).write_bytes(png)

def package_assets():
    pack=ROOT/"assets"/"wurmrail-models.jar"
    with zipfile.ZipFile(pack,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(OUT.rglob('*')):
            if p.is_file():
                info=zipfile.ZipInfo(p.relative_to(OUT).as_posix(),date_time=(2026,10,15,0,0,0)); info.compress_type=zipfile.ZIP_DEFLATED
                z.writestr(info,p.read_bytes(),compresslevel=6)
    return pack

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--previews",action="store_true"); args=ap.parse_args()
    OUT.mkdir(parents=True,exist_ok=True); preview=ROOT/"assets"/"previews"; preview.mkdir(parents=True,exist_ok=True); write_textures()
    manifest={"format":"COLLADA 1.4.1","original_art":True,"units":"metres",
              "axes":"X east; Y up; Z south; vehicle forward +Z before moving-item yaw",
              "authored_axes":"X east; Y south; Z up; vehicle forward -Y",
              "native_axis_bake":"track (x,y,z) -> (x,z,y); vehicle (x,y,z) -> (-x,z,-y); reversed triangle winding",
              "direction_bits":["N","NE","E","SE","S","SW","W","NW"],"tile_size":4,
              "gauge_centres":1.10,"railhead_y":.21,"curve":"quadratic Bezier P0=2*A P1=(0,0) P2=2*B in world XY / native XZ","models":{}}
    mappings=["# Original WurmRail assets. No Wurm game art is redistributed."]
    def save(code,name,m):
        vehicle=name in ['locomotive','cart','tankcar','logcar']
        path=OUT/"wurmrail"/(name+".dae"); write_dae(m,path,vehicle)
        mappings.append(code+" = wurmrail/"+name+".dae")
        manifest["models"][code]={"file":"wurmrail/"+name+".dae","triangles":sum(len(f) for f in m.faces.values()),"bounds":native_bounds(m,vehicle),"sha256":hashlib.sha256(path.read_bytes()).hexdigest(),"mounts":native_mounts(m,vehicle),"authored_mounts":m.joints,"server_mounts":server_mounts(m)}
    for name,m in [("locomotive",locomotive()),("cart",cart()),("tankcar",tank_car()),("logcar",log_car())]:
        save("model.wurmrail."+name,name,m)
        write_obj(m,preview/(name+".obj")); write_glb(m,preview/(name+".glb"))
    hopper=bulk_hopper();save("model.wurmrail.bulkhopper","bulkhopper",hopper)
    write_obj(hopper,preview/"bulkhopper.obj");write_glb(hopper,preview/"bulkhopper.glb")
    drill=steam_rock_drill();save("model.wurmrail.steamrockdrill","steamrockdrill",drill)
    write_obj(drill,preview/"steamrockdrill.obj");write_glb(drill,preview/"steamrockdrill.glb")
    industrial=industrial_water_tank();save("model.wurmrail.industrialwatertank","industrialwatertank",industrial)
    write_obj(industrial,preview/"industrialwatertank.obj");write_glb(industrial,preview/"industrialwatertank.glb")
    bunk=log_bunk();save("model.wurmrail.logbunk","logbunk",bunk)
    write_obj(bunk,preview/"logbunk.obj");write_glb(bunk,preview/"logbunk.glb")
    yarder=steam_yarder();save("model.wurmrail.steamyarder","steamyarder",yarder)
    write_obj(yarder,preview/"steamyarder.obj");write_glb(yarder,preview/"steamyarder.glb")
    pump=steam_water_pump();save("model.wurmrail.steamwaterpump","steamwaterpump",pump)
    write_obj(pump,preview/"steamwaterpump.obj");write_glb(pump,preview/"steamwaterpump.glb")
    for mode in ["pickup","dropoff"]:
        save("model.wurmrail.platform."+mode,"platform_"+mode,passenger_platform(mode=="pickup"))
    for mask in [0]+[sum(1<<i for i in pair) for pair in itertools.combinations(range(8),2)]:
        save("model.wurmrail.alignment."+str(mask),"alignment_"+str(mask),junction_alignment(mask))
    for approach in range(8):
        for green in [False,True]:
            aspect="green" if green else "red"
            save("model.wurmrail.signal."+str(approach)+"."+aspect,"signal_"+str(approach)+"_"+aspect,railway_signal(approach,green))
    for axis in range(4):
        save("model.wurmrail.trestledeck."+str(axis), "trestledeck_"+str(axis), trestle_deck(axis))
    save("model.wurmrail.trestlecurve", "trestlecurve", trestle_curve())
    for height in [1,2,4,8,16,32]:
        save("model.wurmrail.trestlebent."+str(height), "trestlebent_"+str(height), trestle_bent(height))
    for mask in range(256):
        m=track(mask); save("model.wurmrail.track."+str(mask),"track_"+str(mask),m)
        for variant,detail in [("junction",switch_detail),("station",station_detail)]:
            n=Mesh(); n.merge(m); detail(n,mask)
            save("model.wurmrail."+variant+"."+str(mask),variant+"_"+str(mask),n)
        if mask in [0,1,17,34,5,3,9,21,85,255]:
            write_glb(m,preview/("track_"+str(mask)+".glb"))
    write_properties()
    (OUT/"mappings.txt").write_text("\n".join(mappings)+"\n",encoding="utf-8")
    (ROOT/"assets"/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    (preview/"wurmrail.mtl").write_text("\n".join("newmtl "+n+"\nKd "+floats(c)+"\nKs "+floats((s,s,s))+"\nNs "+str(sh)+"\n" for n,(c,s,sh) in MATERIALS.items()),encoding="utf-8")
    pack=package_assets()
    print(f"Generated {len(manifest['models'])} original native game models; pack {pack.stat().st_size:,} bytes")
    if args.previews:
        render_previews()

def render_previews():
    # Pillow/Numpy are optional build-time dependencies; the game art generator
    # and pack have no runtime dependencies. True triangle depth sorting and
    # directional lighting make these inspectable views of the shipped meshes.
    import numpy as np
    from PIL import Image,ImageDraw,ImageFont
    P=ROOT/"assets"/"previews"
    fontpath=Path("C:/Windows/Fonts/segoeui.ttf")
    def font(n): return ImageFont.truetype(str(fontpath),n) if fontpath.exists() else ImageFont.load_default()
    def render(mesh,w,h,scale,view=(1,-1,.85)):
        pixels=np.zeros((h,w,3),dtype=np.uint8); pixels[:]=(24,31,33)
        depth=np.full((h,w),-np.inf,dtype=np.float64)
        forward=unit(view)
        if abs(forward[2])>.999:
            right=(1,0,0); up=(0,-1,0)
        else:
            right=unit(cross((0,0,1),forward)); up=cross(forward,right)
        triangles=[]; pts=[v for fs in mesh.faces.values() for f in fs for v in f]
        center=mul(vecadd(tuple(min(v[k] for v in pts) for k in range(3)),tuple(max(v[k] for v in pts) for k in range(3))),.5)
        projected=[(dot(vecsub(v,center),right),dot(vecsub(v,center),up)) for v in pts]
        ex=max(abs(p[0]) for p in projected); ey=max(abs(p[1]) for p in projected)
        scale=min(scale,(w-35)/(2*ex),(h-65)/(2*ey))
        light=unit((-.6,-.8,1.6))
        for mat,fs in mesh.faces.items():
            for face in fs:
                n=unit(cross(vecsub(face[1],face[0]),vecsub(face[2],face[0])))
                if dot(n,forward)<-.02: continue
                shade=.45+.55*max(0,dot(n,light)); col=tuple(min(255,round((v**.60)*255*shade)) for v in MATERIALS[mat][0])
                pp=[vecsub(v,center) for v in face]
                xy=[(w/2+dot(v,right)*scale,h/2-dot(v,up)*scale) for v in pp]
                zz=[dot(v,forward) for v in pp]
                (x0,y0),(x1,y1),(x2,y2)=xy
                xmin=max(0,int(math.floor(min(p[0] for p in xy)))); xmax=min(w-1,int(math.ceil(max(p[0] for p in xy))))
                ymin=max(0,int(math.floor(min(p[1] for p in xy)))); ymax=min(h-1,int(math.ceil(max(p[1] for p in xy))))
                denom=(y1-y2)*(x0-x2)+(x2-x1)*(y0-y2)
                if abs(denom)<1e-8 or xmin>xmax or ymin>ymax: continue
                yy,xx=np.mgrid[ymin:ymax+1,xmin:xmax+1]; xx=xx+.5; yy=yy+.5
                aa=((y1-y2)*(xx-x2)+(x2-x1)*(yy-y2))/denom
                bb=((y2-y0)*(xx-x2)+(x0-x2)*(yy-y2))/denom; cc=1-aa-bb
                z=aa*zz[0]+bb*zz[1]+cc*zz[2]
                chunk=depth[ymin:ymax+1,xmin:xmax+1]
                mask=(aa>=-1e-6)&(bb>=-1e-6)&(cc>=-1e-6)&(z>chunk)
                chunk[mask]=z[mask]; pixels[ymin:ymax+1,xmin:xmax+1][mask]=col
        return Image.fromarray(pixels)
    canvas=Image.new('RGB',(1600,1100),(15,23,25)); d=ImageDraw.Draw(canvas)
    d.text((58,35),"WURM RAIL",font=font(42),fill=(225,197,137))
    d.text((60,88),"Original game models  /  eight directions  /  curved rail geometry",font=font(20),fill=(177,190,189))
    for title,m,x,y,w,h,s in [("COAL STEAM LOCOMOTIVE",locomotive(),35,145,850,500,145),
                              ("OPEN CARGO + PASSENGER WAGON",cart(),905,145,660,500,130)]:
        im=render(m,w,h,s); canvas.paste(im,(x,y)); d.text((x+20,y+h-35),title,font=font(18),fill=(206,216,210))
    specs=[("STRAIGHT",17),("DIAGONAL",34),("45° BEND",9),("90° BEND",5),("135° BEND",3),("JUNCTION",21)]
    for i,(title,mask) in enumerate(specs):
        m=track(mask)
        if mask==21: switch_detail(m,mask)
        im=render(m,240,295,48,view=(.4,-.7,1.4)); x=55+i*254; y=710
        canvas.paste(im,(x,y)); d.text((x+8,y+270),title,font=font(17),fill=(206,216,210))
    d.text((60,1040),"Native COLLADA 1.4.1  •  Steel rails, timber sleepers, coupling buffers and four seat mounts per vehicle",font=font(19),fill=(149,166,164))
    canvas.save(P/"wurmrail-models.png")
    # Full top-down curve matrix makes every 8-direction two-exit case visible.
    chart=Image.new('RGB',(1440,1260),(17,25,27)); dc=ImageDraw.Draw(chart)
    dc.text((35,25),"ALL 28 TWO-EXIT TRACK SHAPES",font=font(29),fill=(225,197,137))
    names=["N","NE","E","SE","S","SW","W","NW"]
    for idx,(a,b) in enumerate(itertools.combinations(range(8),2)):
        im=render(track((1<<a)|(1<<b)),195,235,35,view=(0,0,1))
        x=25+(idx%7)*200; y=95+(idx//7)*275; chart.paste(im,(x,y))
        dc.text((x+12,y+235),names[a]+" ↔ "+names[b],font=font(18),fill=(197,211,204))
    chart.save(P/"track-shapes.png")
    hopper_preview=render(bulk_hopper(),900,900,180,view=(1,-1,.85))
    hd=ImageDraw.Draw(hopper_preview)
    hd.text((30,25),"WURMRAIL BULK HOPPER",font=font(28),fill=(225,197,137))
    hd.text((30,855),"Original static model / real resource inventory",font=font(19),fill=(177,190,189))
    hopper_preview.save(P/"bulkhopper.png")
    drill_preview=render(steam_rock_drill(),900,800,210,view=(1,-1,.85))
    dd=ImageDraw.Draw(drill_preview)
    dd.text((30,25),"WURMRAIL STEAM ROCK DRILL",font=font(28),fill=(225,197,137))
    dd.text((30,755),"Four cardinal spindles / original static model",font=font(19),fill=(177,190,189))
    drill_preview.save(P/"steamrockdrill.png")
    tank_preview=render(tank_car(),1000,800,200,view=(1,-1,.65))
    td=ImageDraw.Draw(tank_preview)
    td.text((30,25),"WURMRAIL LIQUID TANK CAR",font=font(28),fill=(225,197,137))
    td.text((30,755),"Original rail vehicle / four end-platform passenger mounts",font=font(19),fill=(177,190,189))
    tank_preview.save(P/"tankcar.png")
    log_preview=render(log_car(),1000,800,200,view=(1,-1,.65))
    ld=ImageDraw.Draw(log_preview)
    ld.text((30,25),"WURMRAIL RAW TIMBER LOG CAR",font=font(28),fill=(225,197,137))
    ld.text((30,755),"Original stake wagon / real timber inventory / four end-platform mounts",font=font(19),fill=(177,190,189))
    log_preview.save(P/"logcar.png")
    industrial_preview=render(industrial_water_tank(),1000,1000,150,view=(1,-1,.9))
    wd=ImageDraw.Draw(industrial_preview)
    wd.text((30,25),"WURMRAIL INDUSTRIAL WATER TANK",font=font(28),fill=(225,197,137))
    wd.text((30,955),"Original static water storage / filling and consumer outlets",font=font(19),fill=(177,190,189))
    industrial_preview.save(P/"industrialwatertank.png")
    for mesh,title,name in [(log_bunk(),"WURMRAIL LOG BUNK","logbunk"),(steam_yarder(),"WURMRAIL STEAM YARDER","steamyarder"),(steam_water_pump(),"WURMRAIL STEAM WATER PUMP","steamwaterpump")]:
        forestry_preview=render(mesh,1000,900,200,view=(1,-1,.8))
        fd=ImageDraw.Draw(forestry_preview)
        fd.text((30,25),title,font=font(28),fill=(225,197,137))
        fd.text((30,855),"Original static model / forestry automation",font=font(19),fill=(177,190,189))
        forestry_preview.save(P/(name+".png"))

if __name__=="__main__": main()
