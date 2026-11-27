package com.ampylink.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class DeviceHubActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        int inset = Cyber.dp(this, 24);
        body.setPadding(inset, inset, inset, inset);
        body.setBackgroundColor(Cyber.BG);
        scroll.addView(body);
        body.addView(Cyber.label(this, "AMPY▲LINK", 32, Cyber.CYAN));
        body.addView(Neon.rule(this));
        addDestination(body, "Fotocamera e stampa", "Canon EOS · Galleria · Kodak Mini 2 Retro", Cyber.MAGENTA, GalleryActivity.class);
        addDestination(body, "AmpyOrb Core", "Condividi lo schermo o controlla un dispositivo in LAN", Cyber.CYAN, io.ampy.link.MainActivity.class);
        setContentView(scroll);
    }

    private void addDestination(LinearLayout body, String title, String subtitle, int color, Class<?> target) {
        TextView action = Cyber.button(this, title, color);
        action.setTextSize(17);
        action.setMinHeight(Cyber.dp(this, 64));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = Cyber.dp(this, 32);
        body.addView(action, layout);
        action.setOnClickListener(v -> startActivity(new Intent(this, target)));
        TextView caption = Neon.caption(this, subtitle, 13);
        caption.setPadding(0, Cyber.dp(this, 8), 0, 0);
        body.addView(caption);
    }
}
