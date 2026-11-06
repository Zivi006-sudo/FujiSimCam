"""Fuji-style film simulation colour model -> 3D LUTs. Works in OKLab/OKLCh."""
import numpy as np
from oklab import *

def smooth(e0,e1,x):
    t=np.clip((x-e0)/(e1-e0),0,1); return t*t*(3-2*t)

def hue_w(h,center,width):
    d=np.abs((h-center+180)%360-180); return np.exp(-0.5*(d/width)**2)

# Hue anchors (OKLCh degrees): red 29, orange 55, yellow 105, green 142, cyan 195, blue 260, magenta 330
def make(name, *, contrast=0.0, toe=0.0, shoulder=0.0, bright=0.0,
         chroma=1.0, bands=(), hi_desat=0.0, sh_desat=0.0,
         sh_tint=(0,0), hi_tint=(0,0), mid_tint=(0,0)):
    return dict(name=name, contrast=contrast, toe=toe, shoulder=shoulder, bright=bright, chroma=chroma,
                bands=bands, hi_desat=hi_desat, sh_desat=sh_desat, sh_tint=sh_tint, hi_tint=hi_tint, mid_tint=mid_tint)

def apply(rgb, p):
    """rgb: (...,3) sRGB 0..1 -> sRGB 0..1"""
    lab=lin_to_oklab(srgb_to_lin(np.clip(rgb,0,1)))
    L,a,b=lab[...,0],lab[...,1],lab[...,2]
    C=np.hypot(a,b); h=np.degrees(np.arctan2(b,a))%360
    L0=L.copy()
    # --- tone (on OKLab L, endpoints fixed) ---
    s=1/(1+np.exp(-(L-0.5)*6)); s=(s-1/(1+np.exp(3)))/(1/(1+np.exp(-3))-1/(1+np.exp(3)))
    L=L+(s-L)*p['contrast']
    L=L+p['bright']*np.sin(np.pi*np.clip(L,0,1))
    L=p['toe']+L*(1-p['toe'])                       # lifted blacks
    k=0.72                                           # shoulder: soft highlight roll-off
    over=np.clip(L-k,0,None)
    L=np.where(L>k, k+over*(1-p['shoulder']*over/(1-k)), L)
    # --- colour ---
    dh=np.zeros_like(h); cs=np.ones_like(h)*p['chroma']; dL=np.zeros_like(h)
    for (center,width,shift,csc,lum) in p['bands']:
        w=hue_w(h,center,width)
        dh+=w*shift; cs*=1+w*(csc-1); dL+=w*lum
    cw=smooth(0.0,0.08,C)                            # don't twist neutrals
    h=h+dh*cw
    C=C*cs
    L=L+dL*smooth(0.02,0.12,C)
    C=C*(1-p['hi_desat']*smooth(0.72,0.98,L))*(1-p['sh_desat']*(1-smooth(0.05,0.35,L)))
    a=C*np.cos(np.radians(h)); b=C*np.sin(np.radians(h))
    ws=1-smooth(0.15,0.55,L); wh=smooth(0.5,0.95,L); wm=np.exp(-0.5*((L-0.55)/0.18)**2)
    a=a+p['sh_tint'][0]*ws+p['hi_tint'][0]*wh+p['mid_tint'][0]*wm
    b=b+p['sh_tint'][1]*ws+p['hi_tint'][1]*wh+p['mid_tint'][1]*wm
    lab=np.stack([np.clip(L,0,1),a,b],-1)
    out=oklab_to_lin(lab)
    # gamut: pull chroma toward grey until in range
    for _ in range(8):
        bad=(out<0).any(-1)|(out>1).any(-1)
        if not bad.any(): break
        lab[...,1:]=np.where(bad[...,None],lab[...,1:]*0.88,lab[...,1:])
        out=oklab_to_lin(lab)
    return np.clip(lin_to_srgb(np.clip(out,0,1)),0,1)

# Bands: (hue center, width, hue shift deg, chroma scale, lightness offset)
SIMS = [
 make('provia', contrast=0.12, chroma=1.08, shoulder=0.35,
      bands=((260,25,-3,1.05,-0.01),(142,25,-3,1.03,0),)),
 make('velvia', contrast=0.32, chroma=1.32, shoulder=0.3,
      bands=((29,18,-6,1.08,-0.02),(260,25,-4,1.12,-0.05),(142,25,-4,1.12,-0.03),(105,20,0,1.1,0),(330,25,0,1.12,-0.02))),
 make('astia', contrast=0.02, chroma=1.1, shoulder=0.5, toe=0.01,
      bands=((29,20,2,0.95,0.01),(55,18,0,0.95,0.01),(260,25,-3,1.12,-0.02),(142,25,0,1.08,0))),
 make('classic_chrome', contrast=0.2, chroma=0.8, shoulder=0.55, toe=0.015,
      bands=((29,18,8,0.9,-0.01),(55,18,4,0.88,0),(105,22,-6,0.72,0),(142,28,-18,0.6,-0.03),
             (195,25,-6,0.7,-0.02),(260,28,-14,0.62,-0.05),(330,25,0,0.75,0)),
      hi_desat=0.45, sh_tint=(-0.006,0.004), hi_tint=(0.004,0.012)),
 make('reala_ace', contrast=0.16, chroma=1.06, shoulder=0.45,
      bands=((29,18,2,1.0,0),(142,25,-3,1.0,0),(260,25,-3,1.0,-0.01))),
 make('pro_neg_hi', contrast=0.14, chroma=0.95, shoulder=0.45,
      bands=((29,20,3,0.98,0.01),(55,18,0,1.0,0.01),(260,25,-3,0.95,0))),
 make('pro_neg_std', contrast=-0.04, chroma=0.85, shoulder=0.55, toe=0.01,
      bands=((29,20,2,0.95,0.01),(260,25,-2,0.9,0))),
 make('classic_neg', contrast=0.32, chroma=0.82, shoulder=0.6, toe=0.02,
      bands=((29,16,6,1.06,-0.01),(55,16,0,0.98,-0.01),(105,20,-6,0.75,-0.04),(142,26,-10,0.5,-0.07),
             (195,24,-8,0.6,-0.03),(260,30,-10,0.68,-0.05),(330,25,8,0.7,0)),
      hi_desat=0.75, sh_desat=0.25, sh_tint=(-0.008,0.008), hi_tint=(0.006,0.016), mid_tint=(0.0,0.004)),
 make('nostalgic_neg', contrast=0.1, chroma=0.95, shoulder=0.65, toe=0.015,
      bands=((29,16,10,0.98,0),(55,16,2,1.05,0.01),(105,20,-6,0.85,0),(142,26,-14,0.62,-0.02),
             (195,24,-6,0.65,-0.01),(260,30,-10,0.6,-0.03)),
      hi_desat=0.55, sh_tint=(0.002,0.006), hi_tint=(0.012,0.03), mid_tint=(0.003,0.008)),
 make('eterna', contrast=-0.22, chroma=0.62, shoulder=0.7, toe=0.035,
      bands=((29,18,6,0.95,0),(55,18,2,1.0,0),(142,26,12,0.7,0),(260,28,-8,0.75,0)),
      hi_desat=0.4, sh_tint=(-0.01,-0.004), hi_tint=(0.004,0.008)),
 make('natural', contrast=0.06, chroma=0.94, shoulder=0.12, toe=0.004,
      bands=((29,18,1,0.97,0),(55,18,0,0.97,0),(142,26,-2,0.86,-0.01),(195,24,0,0.9,0),(260,28,-2,0.86,-0.01),(330,25,0,0.92,0)),
      hi_desat=0.25),
 make('eterna_bb', contrast=0.42, chroma=0.36, shoulder=0.3, toe=0.005,
      bands=(), hi_desat=0.3, sh_tint=(-0.004,-0.002)),
]

def identity_grid(n=33):
    r=np.linspace(0,1,n)
    B,G,R=np.meshgrid(r,r,r,indexing='ij')   # .cube order: R fastest
    return np.stack([R,G,B],-1).reshape(-1,3)

def write_cube(path,p,n=33):
    grid=identity_grid(n); out=apply(grid,p)
    with open(path,'w') as f:
        f.write(f'TITLE "{p["name"]}"\n# FujiSim Cam film simulation (approximation, tuned to reference photos)\nLUT_3D_SIZE {n}\n')
        for v in out: f.write(f'{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n')
