import numpy as np, sys
from PIL import Image, ImageDraw
import sims
from pipeline import PHONE
base='/usr/local/lib/python3.13/site-packages/skimage/data/'
imgs=[Image.open(base+n).convert('RGB').resize((200,150)) for n in ['coffee.png','astronaut.png','chelsea.png']]
chart=np.zeros((150,220,3))
keys=list(PHONE)
for i,k in enumerate(keys):
    r,c=divmod(i,4); chart[r*50:(r+1)*50, c*55:(c+1)*55]=np.array(PHONE[k])/255
grad=np.tile(np.linspace(0,1,220)[None,:,None],(1,1,3))
chart[140:150]=grad
srcs=[chart]+[np.asarray(im)/255 for im in imgs]
names=['original']+[p['name'] for p in sims.SIMS]
W=sum(s.shape[1] for s in srcs)+10*len(srcs); H=(150+18)*len(names)
sheet=Image.new('RGB',(W+110,H),(20,20,20)); d=ImageDraw.Draw(sheet)
for ri,n in enumerate(names):
    y=ri*168; d.text((5,y+70),n,fill=(230,230,230)); x=110
    for s in srcs:
        o=s if n=='original' else sims.apply(s,[p for p in sims.SIMS if p['name']==n][0])
        sheet.paste(Image.fromarray((np.clip(o,0,1)*255).astype('uint8')),(x,y)); x+=s.shape[1]+10
sheet.save(sys.argv[1] if len(sys.argv)>1 else 'sheet.png')
