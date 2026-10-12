#!/usr/bin/env python3
"""Render a PNG preview of the compiled world (QA aid, not shipped in the APK)."""
import sys, os, numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from world_format import Reader
import build_world as bw, mumbai_geo as geo

r = Reader("android/src/main/assets/world/terrain.mwl")
lw, lh = r.u32(), r.u32(); lres = r.f32(); lx0, lz0 = r.f64(), r.f64()
lu = np.frombuffer(r.blob(), dtype=np.uint8).reshape(lh, lw)
hw, hh = r.u32(), r.u32(); hres = r.f32(); hx0, hz0 = r.f64(), r.f64()
ht = np.frombuffer(r.blob(), dtype="<i2").reshape(hh, hw).astype(np.float32)/10.0

PAL = {0:(14,38,66),1:(28,74,112),2:(32,70,96),3:(40,86,100),4:(44,110,140),5:(50,100,130),
 6:(226,206,160),7:(120,110,92),8:(60,92,60),9:(34,84,44),10:(74,128,70),11:(140,146,110),
 12:(170,160,140),13:(186,172,150),14:(196,176,152),15:(206,170,140),16:(226,196,170),
 17:(130,120,118),18:(160,160,164),19:(110,104,100)}
img = np.zeros((lh, lw, 3), dtype=np.uint8)
for k, v in PAL.items():
    img[lu == k] = v
Image.fromarray(img).save("/tmp/world_landuse.png")

# hillshade of elevation
hs = np.zeros((hh, hw, 3), dtype=np.uint8)
gy, gx = np.gradient(ht)
shade = np.clip(0.6 + (gx*0.6 - gy*0.6)/8.0, 0, 1)
col = np.clip(ht/120.0, 0, 1)
hs[...,0] = (shade*(60+col*180)).astype(np.uint8)
hs[...,1] = (shade*(90+col*140)).astype(np.uint8)
hs[...,2] = (shade*(70+col*110)).astype(np.uint8)
hs[ht <= 0] = (18, 44, 72)
Image.fromarray(hs).save("/tmp/world_height.png")

# overlay roads/rail on landuse
im = Image.fromarray(img).convert("RGB")
d = ImageDraw.Draw(im)
def cell(x, z): return ((x - lx0)/lres, (z - lz0)/lres)
for (name, cls, lanes, layer, pts) in geo.ROADS:
    xy = [cell(*bw.to_xz(a, b)) for (a, b) in pts]
    d.line(xy, fill=(250,180,60), width=3 if cls==0 else 2)
for (name, kind, layer, pts) in geo.RAILWAYS:
    xy = [cell(*bw.to_xz(a, b)) for (a, b) in pts]
    d.line(xy, fill=(230,60,90), width=2)
for (key, label, lat, lon) in geo.CHECKPOINTS:
    x, y = cell(*bw.to_xz(lat, lon))
    d.ellipse([x-7,y-7,x+7,y+7], outline=(255,255,255), width=2)
im = im.resize((lw//2, lh//2))
im.save("/tmp/world_overlay.png")
print("landuse hist:", {k:int((lu==k).sum()) for k in sorted(set(lu.flatten().tolist()))})
print("land cells:", int((~np.isin(lu, (0,1,2,3,4,5))).sum()), "of", lu.size)
print("height range:", float(ht.min()), float(ht.max()))
print("saved /tmp/world_overlay.png", im.size)
