package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Ordinary page, used to simulate another App to come to the front. The test APK page runs in the test
 * package's own process without the Kotlin standard library, so it is written in Java.
 */
public class PlainTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("plain page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
