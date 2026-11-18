package com.lawliet.minitouch.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.lawliet.minitouch.R;
import com.lawliet.minitouch.core.PrefsRepository;

/** 用可获得输入焦点的对话框编辑悬浮面板的应用名称。 */
public class EditAppNameActivity extends AppCompatActivity {
    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_ORIGINAL_NAME = "original_name";
    private static final String STATE_DRAFT = "draft";

    private EditText nameInput;
    private AlertDialog dialog;

    public static Intent createIntent(Context context, String packageName, String originalName) {
        return new Intent(context, EditAppNameActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_ORIGINAL_NAME, originalName);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        String originalName = getIntent().getStringExtra(EXTRA_ORIGINAL_NAME);
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(originalName)) {
            finish();
            return;
        }

        PrefsRepository prefs = PrefsRepository.get(this);
        String currentName = prefs.getAppDisplayName(packageName, originalName);
        nameInput = new EditText(this);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        nameInput.setSingleLine(true);
        nameInput.setHint(originalName);
        nameInput.setText(savedInstanceState == null
                ? currentName : savedInstanceState.getString(STATE_DRAFT, currentName));
        nameInput.setSelectAllOnFocus(true);

        FrameLayout content = new FrameLayout(this);
        int inset = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
        content.addView(nameInput, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.edit_app_name_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.edit_app_name_reset,
                        (d, which) -> prefs.setAppDisplayName(packageName, ""))
                .setPositiveButton(R.string.edit_app_name_save, (d, which) -> {
                    String name = nameInput.getText().toString().trim();
                    prefs.setAppDisplayName(packageName, name.equals(originalName) ? "" : name);
                })
                .create();
        dialog.setOnDismissListener(d -> finish());
        nameInput.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (nameInput != null) {
            outState.putString(STATE_DRAFT, nameInput.getText().toString());
        }
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
        super.onDestroy();
    }
}
