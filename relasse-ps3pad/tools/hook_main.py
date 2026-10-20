#!/usr/bin/env python3
import pathlib, re, sys
p=pathlib.Path(sys.argv[1]); s=p.read_text()
if 'Lcom/bozpad/BozPad;->dispatchKeyEvent' in s:
    raise SystemExit('Main already hooked')
block='''

.method public dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 1
    :ps3_try_start
    invoke-static {p0, p1}, Lcom/bozpad/BozPad;->dispatchKeyEvent(Lcom/ideaworks3d/marmalade/LoaderActivity;Landroid/view/KeyEvent;)Z
    move-result v0
    :ps3_try_end
    .catch Ljava/lang/Throwable; {:ps3_try_start .. :ps3_try_end} :ps3_error
    if-eqz v0, :ps3_original
    const/4 v0, 0x1
    return v0
    :ps3_error
    :ps3_original
    invoke-super {p0, p1}, Lcom/ideaworks3d/studio/IsDeviceActivity;->dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    move-result v0
    return v0
.end method

.method public onGenericMotionEvent(Landroid/view/MotionEvent;)Z
    .locals 1
    :ps3_motion_try_start
    invoke-static {p0, p1}, Lcom/bozpad/BozPad;->onGenericMotionEvent(Lcom/ideaworks3d/marmalade/LoaderActivity;Landroid/view/MotionEvent;)Z
    move-result v0
    :ps3_motion_try_end
    .catch Ljava/lang/Throwable; {:ps3_motion_try_start .. :ps3_motion_try_end} :ps3_motion_error
    if-eqz v0, :ps3_motion_original
    const/4 v0, 0x1
    return v0
    :ps3_motion_error
    :ps3_motion_original
    invoke-super {p0, p1}, Lcom/ideaworks3d/studio/IsDeviceActivity;->onGenericMotionEvent(Landroid/view/MotionEvent;)Z
    move-result v0
    return v0
.end method
'''
s=s.rstrip()+block+'\n';p.write_text(s)
print('hooked',p)
