from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else 'google_drive/my game.html')
s=p.read_text()
start=s.index('const ZM = {', s.index('Operation 935 zombie visual set'))
end=s.index('const zI = ZP.map', start)
new=r'''const ZM = {
  cloth: new THREE.MeshStandardMaterial({ color:0x5d6956, map:zClothTex, bumpMap:zClothTex, bumpScale:.025, roughness:.96, metalness:0 }),
  flesh: new THREE.MeshStandardMaterial({ color:0x8d9077, map:zSkinTex, bumpMap:zSkinTex, bumpScale:.045, roughness:.9, metalness:0 }),
  dark:  new THREE.MeshStandardMaterial({ color:0x20221d, map:zClothTex, roughness:1, metalness:0 }),
  bone:  new THREE.MeshStandardMaterial({ color:0xc8b991, roughness:.78, metalness:0 }),
  wound: new THREE.MeshStandardMaterial({ color:0x52100d, roughness:.72, metalness:0 }),
  eye:   new THREE.MeshBasicMaterial({ color:0xff351d, toneMapped:false }),
};
const bg=(w,h,d,top=false)=>{const g=new THREE.BoxGeometry(w,h,d,1,1,1);if(top)g.translate(0,-h/2,0);return g;};
const cg=(rt,rb,h,top=false,n=7)=>{const g=new THREE.CylinderGeometry(rt,rb,h,n,1,false);if(top)g.translate(0,-h/2,0);return g;};
const sg=(x,y,z,n=10)=>{const g=new THREE.SphereGeometry(1,n,Math.max(6,n-3));g.scale(x,y,z);return g;};
/* Anatomical low-poly rig. Limb geometry is top-pivoted, so rotation originates
   at shoulders, elbows, hips and knees rather than spinning a whole box in place. */
const ZP = [
  {role:'pelvis',    g:cg(.27,.33,.28,false,8),m:ZM.dark,  o:[0,.86,0]},
  {role:'torso',     g:cg(.31,.39,.62,false,8),m:ZM.cloth, o:[0,1.22,0]},
  {role:'chestWound',g:sg(.24,.18,.025,8),m:ZM.wound,o:[.05,1.30,.315]},
  {role:'neck',      g:cg(.115,.13,.18,false,7),m:ZM.flesh,o:[0,1.57,0]},
  {role:'head',      g:sg(.205,.255,.195,10),m:ZM.flesh,o:[0,1.77,.01]},
  {role:'jaw',       g:bg(.25,.11,.22),m:ZM.flesh,o:[0,1.57,.015]},
  {role:'hair',      g:sg(.21,.07,.20,9),m:ZM.dark,o:[0,1.985,.005]},
  {role:'teeth',     g:bg(.17,.035,.024),m:ZM.bone,o:[0,1.605,.211],tint:false},
  {role:'upperArmL', g:cg(.09,.105,.39,true),m:ZM.cloth,o:[-.39,1.48,0]},
  {role:'upperArmR', g:cg(.09,.105,.39,true),m:ZM.cloth,o:[ .39,1.48,0]},
  {role:'foreArmL',  g:cg(.072,.09,.37,true),m:ZM.flesh,o:[-.39,1.10,-.01]},
  {role:'foreArmR',  g:cg(.072,.09,.37,true),m:ZM.flesh,o:[ .39,1.10,-.01]},
  {role:'handL',     g:sg(.10,.13,.075,7),m:ZM.flesh,o:[-.39,.70,-.025]},
  {role:'handR',     g:sg(.10,.13,.075,7),m:ZM.flesh,o:[ .39,.70,-.025]},
  {role:'thighL',    g:cg(.115,.145,.43,true),m:ZM.dark,o:[-.16,.79,0]},
  {role:'thighR',    g:cg(.115,.145,.43,true),m:ZM.dark,o:[ .16,.79,0]},
  {role:'shinL',     g:cg(.085,.11,.40,true),m:ZM.flesh,o:[-.16,.38,0]},
  {role:'shinR',     g:cg(.085,.11,.40,true),m:ZM.flesh,o:[ .16,.38,0]},
  {role:'bootL',     g:bg(.19,.13,.34),m:ZM.dark,o:[-.16,.08,-.075]},
  {role:'bootR',     g:bg(.19,.13,.34),m:ZM.dark,o:[ .16,.08,-.075]},
  {role:'eyeL',      g:sg(.036,.026,.018,7),m:ZM.eye,o:[-.074,1.80,.190],tint:false},
  {role:'eyeR',      g:sg(.036,.026,.018,7),m:ZM.eye,o:[ .074,1.80,.190],tint:false},
];
'''
s=s[:start]+new+s[end:]
old='''      const q=ZP[p], r=q.role;
      let rx=(r==='armL' || r==='handL') ? -1.05+sw*.3
             :(r==='armR'||r==='handR') ? -1.05-sw*.3
             :(r==='legL'||r==='bootL') ? sw
             :(r==='legR'||r==='bootR') ? -sw : 0;
      _p.position.set(q.o[0],q.o[1],q.o[2]);
      _p.rotation.set(rx, r==='head'?Math.sin((z.ph||0)*.37)*.08:0, 0); _p.updateMatrix();'''
newguest='''      const q=ZP[p],r=q.role,left=r.endsWith('L'),right=r.endsWith('R');
      let rx=0,ry=0,rz=0;
      if(r.includes('Arm')||r.startsWith('foreArm')||r.startsWith('hand')) rx=-1.0+(left?sw:-sw)*.32;
      else if(r.startsWith('thigh')||r.startsWith('shin')||r.startsWith('boot')) rx=(left?sw:-sw);
      else if(r==='head'||r==='jaw'||r==='hair'||r==='teeth'||r.startsWith('eye')){ry=Math.sin((z.ph||0)*.37)*.09;rz=Math.sin((z.ph||0)*.21)*.035;}
      _p.position.set(q.o[0],q.o[1],q.o[2]);
      _p.rotation.set(rx,ry,rz); _p.updateMatrix();'''
if old not in s: raise SystemExit('guest pose not found')
s=s.replace(old,newguest)
old='''      const q=ZP[p], r=q.role;
      const left=r.endsWith('L'), right=r.endsWith('R');
      let rx=0, ry=0, rz=0, ox=q.o[0], oy=q.o[1], oz=q.o[2];
      if(r==='armL'||r==='handL') rx=arm+sw*.34;
      else if(r==='armR'||r==='handR') rx=arm-sw*.34;
      else if(r==='legL'||r==='bootL') rx=sw*(z.type==='runner'?1.45:1);
      else if(r==='legR'||r==='bootR') rx=-sw*(z.type==='runner'?1.45:1);
      else if(r==='head'||r==='jaw'||r==='hair'||r==='eyeL'||r==='eyeR'){
        ry=Math.sin(z.ph*.41+i)*.10; rz=Math.sin(z.ph*.23+i)*.045;
        if(r==='jaw') rx=.08+ak*.20;
      } else if(r==='torso'||r==='chest') rz=Math.sin(z.ph*.5+i)*.035;
      if(z.crawl||z.type==='crawl'){
        if(r==='torso'||r==='chest') rx=-1.05;
        if(r.startsWith('arm')||r.startsWith('hand')) rx=-.35+(left?sw:-sw);
        if(r.startsWith('leg')||r.startsWith('boot')) {rx=1.0+(left?-sw:sw);oy-=.23;}
        if(r==='head'||r==='jaw'||r==='hair'||r.startsWith('eye')){oy-=.38;rx=.5;}
      }
      if(z.stun>0){ rz+=(left?-.08:right?.08:0); rx+=Math.sin(z.ph*2)*.12; }
      _p.position.set(ox,oy,oz); _p.rotation.set(rx,ry,rz); _p.updateMatrix();'''
newpose='''      const q=ZP[p],r=q.role,left=r.endsWith('L'),right=r.endsWith('R');
      let rx=0,ry=0,rz=0,ox=q.o[0],oy=q.o[1],oz=q.o[2];
      const run=z.type==='runner'?1.55:1, side=left?1:right?-1:0;
      if(r.startsWith('upperArm')) rx=arm+side*sw*.38;
      else if(r.startsWith('foreArm')) rx=arm-.18-ak*.42+side*sw*.22;
      else if(r.startsWith('hand')) rx=arm-.24-ak*.5+side*sw*.20;
      else if(r.startsWith('thigh')) rx=side*sw*run;
      else if(r.startsWith('shin')) rx=-side*sw*run*.72+Math.max(0,-side*sw)*.38;
      else if(r.startsWith('boot')) rx=side*sw*run*.30;
      else if(r==='head'||r==='jaw'||r==='hair'||r==='teeth'||r.startsWith('eye')){
        ry=Math.sin(z.ph*.41+i)*.11;rz=Math.sin(z.ph*.23+i)*.05;
        if(r==='jaw'||r==='teeth')rx=.06+ak*.24;
      }else if(r==='torso'||r==='chestWound'||r==='neck')rz=Math.sin(z.ph*.5+i)*.04;
      if(z.crawl||z.type==='crawl'){
        if(r==='torso'||r==='chestWound'||r==='neck'||r==='pelvis')rx=-1.03;
        if(r.includes('Arm')||r.startsWith('hand'))rx=-.25+side*sw*.9;
        if(r.startsWith('thigh')||r.startsWith('shin')||r.startsWith('boot')){rx=.9-side*sw*.7;oy-=.28;}
        if(r==='head'||r==='jaw'||r==='hair'||r==='teeth'||r.startsWith('eye')){oy-=.42;oz-=.12;rx=.54;}
      }
      if(z.stun>0){rz+=side*-.10;rx+=Math.sin(z.ph*2)*.14;}
      _p.position.set(ox,oy,oz);_p.rotation.set(rx,ry,rz);_p.updateMatrix();'''
if old not in s: raise SystemExit('main pose not found')
s=s.replace(old,newpose)
# More cinematic atmosphere without adding expensive dynamic shadows on mobile.
s=s.replace("scene.fog = new THREE.Fog(0x3b3226, 26, 120);","scene.fog = new THREE.FogExp2(0x211b18, isMobile ? .0105 : .0085);")
s=s.replace("<span class=\"ztag bulb\">GROUP 935 // MAP 01</span>","<span class=\"ztag bulb\">GROUP 935 // REALISM EDITION</span>")
p.write_text(s)
print('realism upgrade:',len(s),'characters,',s.count("role:'"),'rig parts')
