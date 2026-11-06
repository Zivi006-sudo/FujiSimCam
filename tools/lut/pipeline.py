import numpy as np
from oklab import *
import sims
SIM={p['name']:p for p in sims.SIMS}
def wb_gains(R,B):
    g=np.array([1+0.025*R,1.0,1+0.025*B]); l=0.2126*g[0]+0.7152*g[1]+0.0722*g[2]; return g/l
def tone(x,hl,sh,contrast=0.0,lift=0.0):
    x=np.clip(x,0,1); x=x+(x*x*(3-2*x)-x)*contrast
    hw=np.sin(np.pi*np.clip((x-0.35)/0.65,0,1)); sw=np.sin(np.pi*np.clip(x/0.65,0,1))
    x=np.clip(x+hl*0.035*hw-sh*0.035*sw,0,1); return lift+x*(1-lift)
def run(rgb, sim, R=0,B=0, dr=100, H=0, S=0, color=0, chrome=0, color_step=0.06):
    c=np.asarray(rgb,float)
    c=lin_to_srgb(srgb_to_lin(c)*wb_gains(R,B))
    c=sims.apply(c,SIM[sim])
    L=c@np.array([0.299,0.587,0.114])
    c=L[...,None]+(c-L[...,None])*(1+color_step*color)
    hsvmax=c.max(-1); hsvmin=c.min(-1); chroma=(hsvmax-hsvmin)/np.maximum(hsvmax,1e-6)
    cc=chroma**2*np.clip((L-0.15)/0.45,0,1); c=c*(1-chrome*0.5*0.2*cc)[...,None]
    drc={200:0.75,400:1.5}.get(dr,0)
    return tone(c,H-drc,S)
PHONE={
 'deep sky':(70,130,210),'pale sky':(165,190,220),'white lit':(235,232,225),'cream wall':(225,205,180),
 'brick red':(180,80,60),'orange wall':(205,110,70),'yellow bldg':(215,175,80),'foliage':(90,130,50),
 'shadow grey':(62,63,65),'mid grey':(120,120,118),'skin':(220,170,140),
}
TARGET={'deep sky':(0.52,0.063,248),'pale sky':(0.75,0.012,260),'white lit':(0.74,0.045,75),'cream wall':(0.76,0.058,60),
 'orange wall':(0.55,0.115,44),'brick red':(0.6,0.12,44),'shadow grey':(0.33,0.02,100),'foliage':(0.4,0.03,125),'yellow bldg':(0.6,0.07,62)}
def report(label,**kw):
    print('==',label)
    for k,v in PHONE.items():
        o=run(np.array(v)/255,**kw); l=srgb_to_oklch(o); t=TARGET.get(k)
        ts=f"   target L{t[0]:.2f} C{t[1]:.3f} h{t[2]:.0f}" if t else ''
        print(f"  {k:12s} -> L{l[0]:.2f} C{l[1]:.3f} h{l[2]:4.0f} {tuple(int(x) for x in (o*255).round())}{ts}")
if __name__=='__main__':
    report('Cherry/Pacific (ClassicNeg Auto R+3 B-4 DR200 H-2 S-1 C+3 CCE strong)',sim='classic_neg',R=3,B=-4,dr=200,H=-2,S=-1,color=3,chrome=2)
    report('Cinematic Gold (ClassicNeg Daylight R+4 B-5 DR400 C+3)',sim='classic_neg',R=4,B=-5,dr=400,color=3)
