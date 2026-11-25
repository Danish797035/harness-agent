package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test page for screen reading and input verification:
 * - - The "Results" text is marked as not important for accessibility (like the display area of a
 * calculator), press "7" to only change it;
 * - - Clicking the "empty input box" only changes the input focus;
 * - - "Hot word examples" is a search box that only displays prompts;
 * - - The "changing box" is replaced by a new input control after input (some apps rebuild the input
 * view as soon as the content changes);
 * - - The "fake search box" is just a clickable text. When clicked, the real input box appears and gets
 * focus (this is the case with many homepage search bars).
 * The test APK page runs in the test package's own process without the Kotlin standard library, so it is
 * written in Java.
 */
public class FieldsTestActivity extends Activity {
    private LinearLayout holder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView result = new TextView(this);
        result.setText("0");
        result.setTextSize(32f);
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(result);
        Button seven = new Button(this);
        seven.setText("7");
        seven.setOnClickListener(v -> result.append("7"));
        root.addView(seven);
        EditText empty = new EditText(this);
        empty.setHint("空输入框");
        empty.setContentDescription(null);
        root.addView(empty);
        EditText hinted = new EditText(this);
        hinted.setHint("热词示例");
        root.addView(hinted);
        holder = new LinearLayout(this);
        root.addView(holder);
        holder.addView(swapping(""), fullWidth());
        EditText real = new EditText(this);
        real.setHint("真正的输入框");
        real.setVisibility(View.GONE);
        TextView fake = new TextView(this);
        fake.setText("假搜索框");
        fake.setTextSize(24f);
        fake.setOnClickListener(v -> {
            real.setVisibility(View.VISIBLE);
            real.requestFocus();
        });
        root.addView(fake);
        root.addView(real);
        setContentView(root);
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private EditText swapping(CharSequence initial) {
        EditText field = new EditText(this);
        field.setHint("会换的框");
        field.setText(initial);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String text = s == null ? "" : s.toString();
                if (text.isEmpty()) return;
                field.post(() -> {
                    holder.removeAllViews();
                    EditText next = swapping(text);
                    holder.addView(next, fullWidth());
                    next.requestFocus();
                });
            }
        });
        return field;
    }
}
