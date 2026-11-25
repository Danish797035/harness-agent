package com.wangzan101.mina.a11y;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The rich text editing area (contenteditable) in the web page is like this in the notes and comment
 * boxes of many apps. The editor only recognizes the browser's input events: the text above displays the
 * number of input events received and the current content. The test will use this to determine whether
 * the editor has actually received the input. The test APK runs in its own process without the Kotlin
 * standard library, so this page is written in Java.
 */
public class WebEditorTestActivity extends Activity {
    private TextView status;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("inputs=0 text=");
        status.setTextSize(20f);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "bridge");
        web.loadDataWithBaseURL(null,
            "<html><body><div id=\"ed\" contenteditable=\"true\" aria-label=\"正文\" style=\"min-height:200px;border:1px solid #888\"></div>"
                + "<script>var n=0;document.getElementById('ed').addEventListener('input',function(e){n++;bridge.report(n,e.target.innerText);});</script>"
                + "</body></html>",
            "text/html", "utf-8", null);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(status);
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800));
        setContentView(root);
    }

    private class Bridge {
        @JavascriptInterface
        public void report(int n, String text) {
            runOnUiThread(() -> status.setText("inputs=" + n + " text=" + text.trim()));
        }
    }
}
