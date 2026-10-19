#!/usr/bin/env python3
"""Optional enhanced analog bridge for Universal PS3 Mapper.
Requires USB debugging and adb while playing. No root is used.
Usage: python3 ps3-adb-bridge.py [--device /dev/input/eventN]
"""
import argparse, re, socket, subprocess, sys, threading, time
PORT=37654
KEYS={'BTN_SOUTH':96,'BTN_EAST':97,'BTN_C':98,'BTN_NORTH':99,'BTN_WEST':100,'BTN_Z':101,
      'BTN_TL':102,'BTN_TR':103,'BTN_TL2':104,'BTN_TR2':105,'BTN_THUMBL':106,'BTN_THUMBR':107,
      'BTN_START':108,'BTN_SELECT':109,'BTN_MODE':110,'KEY_UP':19,'KEY_DOWN':20,'KEY_LEFT':21,'KEY_RIGHT':22}
def adb(*args,**kw): return subprocess.run(['adb',*args],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw)
def pick_device(explicit):
    if explicit:return explicit
    out=adb('shell','getevent','-lp').stdout
    blocks=re.split(r'(?=add device \d+:)',out)
    for b in blocks:
        m=re.search(r'(/dev/input/event\d+)',b)
        if m and 'EV_ABS' in b and ('BTN_GAMEPAD' in b or 'BTN_SOUTH' in b):return m.group(1)
    raise SystemExit('No gamepad event device found. Pair the PS3 pad, or pass --device /dev/input/eventN')
def ranges(dev):
    out=adb('shell','getevent','-lp',dev).stdout; result={}
    for axis in ('ABS_RX','ABS_RY','ABS_Z','ABS_RZ'):
        m=re.search(rf'{axis}.*?min\s+(-?\d+),\s+max\s+(-?\d+)',out,re.S)
        if m:result[axis]=(int(m.group(1)),int(m.group(2)))
    return result
def norm(v,lo,hi):
    if hi==lo:return 0.0
    x=(v-lo)*2.0/(hi-lo)-1.0
    return 0.0 if abs(x)<.16 else max(-1.0,min(1.0,x))
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--device');a=ap.parse_args();dev=pick_device(a.device);rs=ranges(dev)
    pair=('ABS_RX','ABS_RY') if 'ABS_RX' in rs and 'ABS_RY' in rs else ('ABS_Z','ABS_RZ')
    print('Using',dev,'right stick',pair,'ranges',rs)
    subprocess.check_call(['adb','forward',f'tcp:{PORT}',f'tcp:{PORT}'])
    sock=socket.create_connection(('127.0.0.1',PORT)); values={pair[0]:0.0,pair[1]:0.0}
    p=subprocess.Popen(['adb','shell','getevent','-lt',dev],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=1)
    try:
        for line in p.stdout:
            ma=re.search(r'EV_ABS\s+(ABS_[A-Z0-9]+)\s+([0-9a-fA-F]+)',line)
            if ma and ma.group(1) in pair:
                name=ma.group(1); raw=int(ma.group(2),16); lo,hi=rs.get(name,(0,255)); values[name]=norm(raw,lo,hi)
                sock.sendall(f'AXIS {values[pair[0]]:.4f} {values[pair[1]]:.4f}\n'.encode());continue
            mk=re.search(r'EV_KEY\s+(BTN_[A-Z0-9]+|KEY_(?:UP|DOWN|LEFT|RIGHT))\s+(DOWN|UP|00000001|00000000)',line)
            if mk and mk.group(1) in KEYS:
                down=mk.group(2) in ('DOWN','00000001');sock.sendall(f'KEY {KEYS[mk.group(1)]} {1 if down else 0}\n'.encode())
    except (BrokenPipeError,KeyboardInterrupt): pass
    finally:p.terminate();sock.close();adb('forward','--remove',f'tcp:{PORT}')
if __name__=='__main__':main()
