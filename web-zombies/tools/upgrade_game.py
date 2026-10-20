from pathlib import Path
import sys
p = Path(sys.argv[1] if len(sys.argv) > 1 else 'google_drive/my game.html')
s=p.read_text()
old=s[s.index("const ZM = {"):s.index("const zI = ZP.map", s.index("const ZM = {"))]
new=r'''/* Operation 935 zombie visual set. The supplied BOZ APK contains only the
   five-megabyte bootstrap archive; its downloadable model/animation OBB is not
   packaged in the APK. These self-contained procedural materials reproduce the
   decayed military silhouette without making this HTML depend on a missing OBB. */
function zombieTex(kind){
  const cv=document.createElement('canvas');cv.width=cv.height=128;
  const g=cv.getContext('2d'), skin=kind==='skin';
  g.fillStyle=skin?'#7f856d':'#394336';g.fillRect(0,0,128,128);
  let seed=skin?935:115;
  const rnd=()=>((seed=(seed*1664525+1013904223)>>>0)/4294967296);
  for(let i=0;i<190;i++){
    const x=rnd()*128,y=rnd()*128,r=1+rnd()*11;
    g.fillStyle=skin
      ? `rgba(${55+(rnd()*70|0)},${20+(rnd()*45|0)},${18+(rnd()*32|0)},${.08+rnd()*.28})`
      : `rgba(${rnd()<.35?125:18},${rnd()<.25?28:45},${18+(rnd()*20|0)},${.08+rnd()*.22})`;
    g.beginPath();g.arc(x,y,r,0,Math.PI*2);g.fill();
  }
  if(!skin){
    g.strokeStyle='rgba(190,190,160,.18)';g.lineWidth=2;
    for(let y=8;y<128;y+=24){g.beginPath();g.moveTo(0,y);g.lineTo(128,y+12);g.stroke();}
  }
  const t=new THREE.CanvasTexture(cv);t.colorSpace=THREE.SRGBColorSpace;
  t.wrapS=t.wrapT=THREE.RepeatWrapping;t.magFilter=THREE.NearestFilter;return t;
}
const zSkinTex=zombieTex('skin'), zClothTex=zombieTex('cloth');
const ZM = {
  cloth: new THREE.MeshLambertMaterial({ color:0x66705d, map:zClothTex }),
  flesh: new THREE.MeshLambertMaterial({ color:0x92957d, map:zSkinTex }),
  dark:  new THREE.MeshLambertMaterial({ color:0x24251f, map:zClothTex }),
  wound: new THREE.MeshLambertMaterial({ color:0x5c1010 }),
  eye:   new THREE.MeshBasicMaterial({ color:0xff3018 }),
};
const pg = (w,h,d,top) => { const g = new THREE.BoxGeometry(w,h,d,1,1,1); if(top) g.translate(0,-h/2,0); return g; };
/* role drives the skeletal animation below. Separate upper/lower limbs allow
   shambling, sprinting, lunging, crawling, hit-stagger and three death poses. */
const ZP = [
  {role:'torso', g:pg(.56,.72,.32), m:ZM.cloth, o:[0,1.14,0]},
  {role:'chest', g:pg(.44,.16,.345),m:ZM.wound,o:[0,1.27,-.01]},
  {role:'head',  g:pg(.31,.33,.29), m:ZM.flesh,o:[0,1.67,.015]},
  {role:'jaw',   g:pg(.24,.10,.25), m:ZM.flesh,o:[0,1.50,-.015]},
  {role:'hair',  g:pg(.32,.07,.30), m:ZM.dark, o:[0,1.855,.01]},
  {role:'armL',  g:pg(.145,.62,.15,1),m:ZM.cloth,o:[-.355,1.46,0]},
  {role:'armR',  g:pg(.145,.62,.15,1),m:ZM.cloth,o:[ .355,1.46,0]},
  {role:'handL', g:pg(.16,.16,.16), m:ZM.flesh,o:[-.355,.80,-.02]},
  {role:'handR', g:pg(.16,.16,.16), m:ZM.flesh,o:[ .355,.80,-.02]},
  {role:'legL',  g:pg(.19,.72,.20,1),m:ZM.dark, o:[-.14,.78,0]},
  {role:'legR',  g:pg(.19,.72,.20,1),m:ZM.dark, o:[ .14,.78,0]},
  {role:'bootL', g:pg(.21,.15,.34), m:ZM.dark, o:[-.14,.08,-.07]},
  {role:'bootR', g:pg(.21,.15,.34), m:ZM.dark, o:[ .14,.08,-.07]},
  {role:'eyeL',  g:pg(.055,.045,.025),m:ZM.eye,o:[-.075,1.70,.158],tint:false},
  {role:'eyeR',  g:pg(.055,.045,.025),m:ZM.eye,o:[ .075,1.70,.158],tint:false},
];
'''
s=s.replace(old,new)
# archetype tints must cover all materials but preserve glowing eyes
s=s.replace("for(let p=0;p<ZP.length;p++){ zI[p].setColorAt(i,_c); zI[p].instanceColor.needsUpdate = true; }",
            "for(let p=0;p<ZP.length;p++){ if(ZP[p].tint!==false) zI[p].setColorAt(i,_c); zI[p].instanceColor.needsUpdate = true; }")
# guest hardcoded pose loop
old_guest='''    for(let p=0;p<ZP.length;p++){
      const rx=p===2 ? -1.1+sw*.3 : p===3 ? -1.1-sw*.3 : p===4 ? sw : p===5 ? -sw : 0;
      _p.position.set(ZP[p].o[0],ZP[p].o[1],ZP[p].o[2]);
      _p.rotation.set(rx,0,0); _p.updateMatrix();
      _m4.multiplyMatrices(_o.matrix,_p.matrix); zI[p].setMatrixAt(i,_m4);
    }'''
new_guest='''    for(let p=0;p<ZP.length;p++){
      const q=ZP[p], r=q.role;
      let rx=(r==='armL' || r==='handL') ? -1.05+sw*.3
             :(r==='armR'||r==='handR') ? -1.05-sw*.3
             :(r==='legL'||r==='bootL') ? sw
             :(r==='legR'||r==='bootR') ? -sw : 0;
      _p.position.set(q.o[0],q.o[1],q.o[2]);
      _p.rotation.set(rx, r==='head'?Math.sin((z.ph||0)*.37)*.08:0, 0); _p.updateMatrix();
      _m4.multiplyMatrices(_o.matrix,_p.matrix); zI[p].setMatrixAt(i,_m4);
    }'''
if old_guest not in s: raise SystemExit('guest block missing')
s=s.replace(old_guest,new_guest)
old_pose='''    for(let p=0;p<ZP.length;p++){
      const rx = p === 2 ? arm + sw*.3 : p === 3 ? arm - sw*.3 : p === 4 ? sw : p === 5 ? -sw : 0;
      _p.position.set(ZP[p].o[0], ZP[p].o[1], ZP[p].o[2]);
      _p.rotation.set(rx,0,0); _p.updateMatrix();
      _m4.multiplyMatrices(_o.matrix, _p.matrix);
      zI[p].setMatrixAt(i, _m4);
    }'''
new_pose='''    for(let p=0;p<ZP.length;p++){
      const q=ZP[p], r=q.role;
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
      _p.position.set(ox,oy,oz); _p.rotation.set(rx,ry,rz); _p.updateMatrix();
      _m4.multiplyMatrices(_o.matrix,_p.matrix); zI[p].setMatrixAt(i,_m4);
    }'''
if old_pose not in s: raise SystemExit('pose block missing')
s=s.replace(old_pose,new_pose)
s=s.replace("for(let p=0;p<ZP.length-1;p++){ zI[p].setColorAt(i,_c); zI[p].instanceColor.needsUpdate = true; }",
            "for(let p=0;p<ZP.length;p++){ if(ZP[p].tint!==false) zI[p].setColorAt(i,_c); zI[p].instanceColor.needsUpdate = true; }")
# Theme identity and provenance note in UI
s=s.replace('<span class="ztag bulb">MAP 01</span>', '<span class="ztag bulb">GROUP 935 // MAP 01</span>')
s=s.replace('SURVIVE · EARN POINTS · BUY PERKS', 'SURVIVE · REBUILD BARRIERS · ESCAPE THE HORDE')
s=s.replace('<h2 class="title bloodtxt -mt-1"', '<h2 class="title bloodtxt -mt-1" data-theme="operation-935"')
p.write_text(s)
print('wrote',p,len(s), 'parts', s.count("role:'"))
