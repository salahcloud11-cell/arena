package com.ps3mod.universal;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Locale;

/** Global, profile-based controller-to-touch mapper. No game modification is used. */
public class PS3AccessibilityService extends AccessibilityService {
    private static final int PORT = 37654;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Target> targets = new ArrayList<Target>();
    private WindowManager wm;
    private View miniBar, editor, cursor;
    private String gamePackage = "default";
    private Target armed;
    private boolean editorOpen, markersVisible = true, barHidden;
    private boolean l3, r3, chord;
    private float cursorX = .5f, cursorY = .5f, axisX, axisY;
    private boolean axisLoop;

    private static class Target {
        int key, clicks = 1; float x = .5f, y = .5f; String name = "TARGET"; Button view;
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected(); wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        loadProfile(); showMiniBar(); startBridge();
        Toast.makeText(this,"Universal PS3 Mapper active",Toast.LENGTH_LONG).show();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String p = e.getPackageName().toString();
        if (p.equals(getPackageName()) || p.startsWith("com.android.systemui")
                || p.startsWith("com.android.settings") || p.equals(gamePackage)) return;
        gamePackage = p; loadProfile(); updateBarTitle();
    }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { remove(editor); remove(miniBar); remove(cursor); super.onDestroy(); }

    @Override protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode(); boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_11) l3 = down;
        if (code == KeyEvent.KEYCODE_BUTTON_THUMBR || code == KeyEvent.KEYCODE_BUTTON_12) r3 = down;
        if (l3 && r3 && !chord) { chord = true; barHidden = false; showMiniBar(); return true; }
        if (!l3 && !r3) chord = false;
        if (armed != null) {
            if (down) { armed.key = code; armed.name = shortKey(code); armed = null; rebuildEditor(); saveProfile(); }
            return true;
        }
        if (ev.getRepeatCount() > 0) return true;
        for (Target t : targets) if (t.key == code) { if (down) tapTarget(t); return true; }
        /* In enhanced ADB mode CROSS clicks the right-stick cursor. */
        if (cursor != null && (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_1)) {
            if (down) tap(cursorX, cursorY, 1); return true;
        }
        return false;
    }

    private String shortKey(int code) {
        String s = KeyEvent.keyCodeToString(code).replace("KEYCODE_BUTTON_","").replace("KEYCODE_","");
        return s + " (" + code + ")";
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private Button button(String s, int sp) { Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(sp); return b; }
    private WindowManager.LayoutParams lp(int w,int h,int flags) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,flags,android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT; return p;
    }
    private void remove(View v) { try { if (v!=null && v.getParent()!=null) wm.removeView(v); } catch(Throwable ignored){} }

    private void showMiniBar() {
        if (wm == null || barHidden || miniBar != null) return;
        LinearLayout bar=new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setPadding(dp(5),0,dp(5),0);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(0xDD102018); bg.setCornerRadius(dp(6)); bg.setStroke(dp(1),0xFF68F0A0); bar.setBackground(bg);
        TextView title=new TextView(this); title.setTag("title"); title.setTextColor(Color.WHITE); title.setTextSize(10); title.setGravity(Gravity.CENTER); bar.addView(title,new LinearLayout.LayoutParams(dp(155),dp(40)));
        Button edit=button("EDIT",10); edit.setOnClickListener(new View.OnClickListener(){public void onClick(View v){openEditor();}}); bar.addView(edit,new LinearLayout.LayoutParams(dp(65),dp(40)));
        Button hide=button("HIDE",10); hide.setOnClickListener(new View.OnClickListener(){public void onClick(View v){barHidden=true; remove(miniBar); miniBar=null; Toast.makeText(PS3AccessibilityService.this,"L3+R3 restores Mapper",Toast.LENGTH_LONG).show();}}); bar.addView(hide,new LinearLayout.LayoutParams(dp(65),dp(40)));
        WindowManager.LayoutParams p=lp(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        p.x=dp(3); p.y=dp(28); wm.addView(bar,p); miniBar=bar; updateBarTitle();
    }
    private void updateBarTitle() {
        if (!(miniBar instanceof LinearLayout)) return;
        View v=((LinearLayout)miniBar).findViewWithTag("title");
        if (v instanceof TextView) ((TextView)v).setText("MAPPER\n"+gamePackage);
    }

    private void openEditor() {
        if (editorOpen) return; editorOpen=true; remove(miniBar); miniBar=null;
        rebuildEditor();
    }
    private void rebuildEditor() {
        if (!editorOpen) return; remove(editor);
        final FrameLayout root=new FrameLayout(this); root.setBackgroundColor(0x22000000);
        LinearLayout tools=new LinearLayout(this); tools.setOrientation(LinearLayout.HORIZONTAL); tools.setBackgroundColor(0xEE102018); tools.setZ(100f);
        Button add=button("+ TARGET",10); add.setOnClickListener(new View.OnClickListener(){public void onClick(View v){Target t=new Target(); t.x=.5f;t.y=.5f;t.name="PRESS KEY";targets.add(t);armed=t;rebuildEditor();}}); tools.addView(add,new LinearLayout.LayoutParams(0,dp(48),1));
        Button visible=button(markersVisible?"TARGETS ON":"TARGETS OFF",9); visible.setOnClickListener(new View.OnClickListener(){public void onClick(View v){markersVisible=!markersVisible; rebuildEditor();}}); tools.addView(visible,new LinearLayout.LayoutParams(0,dp(48),1));
        Button delete=button("DELETE",9); delete.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(armed!=null){targets.remove(armed);armed=null;}else if(!targets.isEmpty())targets.remove(targets.size()-1);saveProfile();rebuildEditor();}}); tools.addView(delete,new LinearLayout.LayoutParams(0,dp(48),1));
        Button save=button("SAVE / PLAY",10); save.setOnClickListener(new View.OnClickListener(){public void onClick(View v){saveProfile();armed=null;editorOpen=false;remove(editor);editor=null;showMiniBar();}}); tools.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));
        root.addView(tools,new FrameLayout.LayoutParams(-1,dp(48)));
        for (int i=0;i<targets.size();i++) addTargetView(root,targets.get(i),i);
        WindowManager.LayoutParams p=lp(-1,-1,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN); wm.addView(root,p); editor=root;
    }
    private void addTargetView(FrameLayout root, final Target t, final int index) {
        Button b=button(t.name+"\n×"+t.clicks,9); t.view=b; b.setAlpha(markersVisible?0.86f:0.25f);
        GradientDrawable bg=new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(0xCC006E45); bg.setStroke(dp(2),armed==t?Color.YELLOW:Color.WHITE); b.setBackground(bg);
        b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){armed=t; Toast.makeText(PS3AccessibilityService.this,"Press the PS3 button for this target",Toast.LENGTH_LONG).show(); rebuildEditor();}});
        b.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){t.clicks=t.clicks%5+1;saveProfile();rebuildEditor();return true;}});
        b.setOnTouchListener(new View.OnTouchListener(){float ox,oy,downX,downY; public boolean onTouch(View v,MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_DOWN){ox=v.getX();oy=v.getY();downX=e.getRawX();downY=e.getRawY();return false;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-downX,dy=e.getRawY()-downY;if(Math.abs(dx)+Math.abs(dy)>dp(8)){v.setX(ox+dx);v.setY(oy+dy);t.x=(v.getX()+v.getWidth()/2f)/root.getWidth();t.y=(v.getY()+v.getHeight()/2f)/root.getHeight();return true;}}return false;}});
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(82),dp(82)); root.addView(b,p);
        b.setX(t.x*getResources().getDisplayMetrics().widthPixels-dp(41)); b.setY(t.y*getResources().getDisplayMetrics().heightPixels-dp(41));
    }

    private void tapTarget(Target t) { tap(t.x,t.y,t.clicks); }
    private void tap(final float nx,final float ny,final int count) {
        for(int i=0;i<count;i++){final long delay=i*135L;ui.postDelayed(new Runnable(){public void run(){
            Path path=new Path(); path.moveTo(nx*getResources().getDisplayMetrics().widthPixels,ny*getResources().getDisplayMetrics().heightPixels);
            GestureDescription.Builder g=new GestureDescription.Builder(); g.addStroke(new GestureDescription.StrokeDescription(path,0,75)); dispatchGesture(g.build(),null,null);
        }},delay);}
    }

    private String prefKey(){return "profile_"+gamePackage.replaceAll("[^A-Za-z0-9_.]","_");}
    private void saveProfile(){
        StringBuilder s=new StringBuilder(); for(Target t:targets){if(s.length()>0)s.append(';');s.append(t.key).append(',').append(t.x).append(',').append(t.y).append(',').append(t.clicks).append(',').append(t.name.replace(","," ").replace(";"," "));}
        getSharedPreferences("profiles",MODE_PRIVATE).edit().putString(prefKey(),s.toString()).apply();
    }
    private void loadProfile(){
        targets.clear(); String raw=getSharedPreferences("profiles",MODE_PRIVATE).getString(prefKey(),""); if(raw.length()==0)return;
        try{for(String row:raw.split(";")){String[] q=row.split(",",5);Target t=new Target();t.key=Integer.parseInt(q[0]);t.x=Float.parseFloat(q[1]);t.y=Float.parseFloat(q[2]);t.clicks=Integer.parseInt(q[3]);t.name=q[4];targets.add(t);}}catch(Throwable ignored){}
    }

    /** Optional enhanced input. The desktop ADB helper forwards normalized right-stick values here. */
    private void startBridge(){new Thread(new Runnable(){public void run(){try{ServerSocket server=new ServerSocket(PORT,1,InetAddress.getByName("127.0.0.1"));while(true){Socket s=server.accept();BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));String line;while((line=r.readLine())!=null)handleBridge(line);s.close();}}catch(Throwable ignored){}}},"PS3-ADB-Bridge").start();}
    private void handleBridge(String line){try{String[] p=line.trim().split("\\s+");if(p.length>=3&&p[0].equals("AXIS")){axisX=Float.parseFloat(p[1]);axisY=Float.parseFloat(p[2]);ui.post(new Runnable(){public void run(){showCursor();startAxisLoop();}});}else if(p.length>=3&&p[0].equals("KEY")){final int k=Integer.parseInt(p[1]);final boolean d=p[2].equals("1");ui.post(new Runnable(){public void run(){KeyEvent e=new KeyEvent(d?KeyEvent.ACTION_DOWN:KeyEvent.ACTION_UP,k);onKeyEvent(e);}});}}catch(Throwable ignored){}}
    private void showCursor(){if(cursor!=null)return;TextView c=new TextView(this);c.setText("⊕");c.setTextColor(Color.WHITE);c.setTextSize(28);c.setGravity(Gravity.CENTER);c.setShadowLayer(4,1,1,Color.BLACK);WindowManager.LayoutParams p=lp(dp(46),dp(46),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);wm.addView(c,p);cursor=c;positionCursor();}
    private void positionCursor(){if(cursor==null)return;WindowManager.LayoutParams p=(WindowManager.LayoutParams)cursor.getLayoutParams();int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;p.x=(int)(cursorX*w)-dp(23);p.y=(int)(cursorY*h)-dp(23);wm.updateViewLayout(cursor,p);}
    private void startAxisLoop(){if(axisLoop)return;axisLoop=true;ui.post(new Runnable(){long last=SystemClock.uptimeMillis();public void run(){long now=SystemClock.uptimeMillis();float dt=Math.min(.05f,(now-last)/1000f);last=now;if(Math.abs(axisX)>.18f||Math.abs(axisY)>.18f){cursorX=Math.max(.03f,Math.min(.97f,cursorX+axisX*dt*.8f));cursorY=Math.max(.05f,Math.min(.95f,cursorY+axisY*dt*.8f));positionCursor();}ui.postDelayed(this,16);}});}
}
