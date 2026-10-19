package com.ps3mod.universal;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(15);
        b.setPadding(12,12,12,12); return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(28,32,28,32); box.setBackgroundColor(Color.rgb(16,24,32));
        TextView title = new TextView(this); title.setText("UNIVERSAL PS3 MAPPER");
        title.setTextColor(0xFF68F0A0); title.setTextSize(25); title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView help = new TextView(this);
        help.setText("Make controller buttons touch any game — without modifying that game.\n\n"
                + "1. Enable the accessibility service below.\n"
                + "2. Open a game. Tap the green MAPPER bar, then EDIT PROFILE.\n"
                + "3. Add and drag targets. Tap a target, then press its PS3 button.\n"
                + "4. Tap its × count to choose 1–5 clicks. Save and play.\n\n"
                + "Profiles are stored separately for each game. Targets can be invisible. "
                + "The optional ADB bridge adds global analog-stick cursor data on Android builds "
                + "that do not expose joystick motion to accessibility services.");
        help.setTextColor(Color.WHITE); help.setTextSize(15); help.setPadding(0,24,0,24);
        box.addView(help);
        Button access = button("OPEN ACCESSIBILITY SETTINGS");
        access.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        box.addView(access);
        Button settings = button("OPEN UNIVERSAL MAPPER SERVICE SETTINGS");
        settings.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS); startActivity(i); }});
        box.addView(settings);
        TextView privacy = new TextView(this);
        privacy.setText("Privacy: profiles stay on this device. The service observes window/package changes "
                + "and controller keys only; it does not read or upload game content.\n\n"
                + "Emergency recovery: launch this app and disable/re-enable the service if an overlay is hidden.");
        privacy.setTextColor(0xFFB0BEC5); privacy.setPadding(0,24,0,12);
        box.addView(privacy);
        scroll.addView(box); setContentView(scroll);
    }
}
