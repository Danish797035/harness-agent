package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Click the button and hand it over to the service ({@link SpillLaunchService}) in another process with
 * NEW_TASK to open {@link #EXTRA_TARGET} that App's startup page: Just like the App opens another App
 * through the background process (service, SDK, jump), the initiated process has no page on Mina's
 * screen, and the system puts it on the home screen by default. Used to reproduce "open to the user's
 * home screen". (When opened from the application context in the same process, Android will display the
 * screen where the top page of the process is located and will not overflow.) The test APK page runs in
 * the test package's own process and does not include the Kotlin standard library, so it is written in
 * Java.
 */
public class SpillTestActivity extends Activity {
    public static final String EXTRA_TARGET = "target";
    /** When given, add another button: This dialog-style page is also opened through the background service. */
    public static final String EXTRA_DIALOG = "dialog";
    /** When given, add another button: open the full-screen account login page through the background service (the test package is an account authenticator). */
    public static final String EXTRA_AUTH = "auth";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra(EXTRA_TARGET);
        String target = extra != null ? extra : "com.android.settings";
        String dialog = getIntent().getStringExtra(EXTRA_DIALOG);
        String auth = getIntent().getStringExtra(EXTRA_AUTH);
        Button button = new Button(this);
        button.setText("open elsewhere");
        button.setTextSize(32f);
        button.setOnClickListener(v ->
            startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_TARGET, target)));
        if (dialog == null && auth == null) {
            setContentView(button);
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(button);
        if (dialog != null) {
            Button dialogButton = new Button(this);
            dialogButton.setText("open dialog");
            dialogButton.setTextSize(32f);
            dialogButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_DIALOG, dialog)));
            root.addView(dialogButton);
        }
        if (auth != null) {
            Button authButton = new Button(this);
            authButton.setText("open auth");
            authButton.setTextSize(32f);
            authButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_AUTH, auth)));
            root.addView(authButton);
        }
        setContentView(root);
    }
}
