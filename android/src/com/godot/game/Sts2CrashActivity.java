package com.godot.game;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import cat.ereza.customactivityoncrash.CustomActivityOnCrash;
import cat.ereza.customactivityoncrash.config.CaocConfig;

public class Sts2CrashActivity extends AppCompatActivity {
	private static final int MAX_CRASH_LOGS = 5;
	private CaocConfig config;
	private String fullErrorDetails;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		StartupHealthTracker.clearPendingLaunchState(this);
		config = CustomActivityOnCrash.getConfigFromIntent(getIntent());
		fullErrorDetails = buildCompactErrorDetails();
		if (savedInstanceState == null) {
			writeCrashLog(fullErrorDetails);
		}
		if (new PayloadManager(this).isStandaloneBuild()) {
			showStandaloneCrashDialog();
			return;
		}
		ExtraSettingsUi.applyPhonePortraitTabletFreeOrientation(this);
		showCrashReport();
	}

	/**
	 * Standalone build (plays like a regular mobile game): no stack trace, no
	 * recovery tools, just a plain notice and a restart into the game. The report
	 * is already saved under files/logs/crash. Long-pressing the message opens the
	 * full crash report (with "Open Recovery Settings") for troubleshooting.
	 */
	private void showStandaloneCrashDialog() {
		setTitle(R.string.godot_project_name_string);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		SystemBarInsetsHelper.hideSystemBars(this);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
			.setMessage(R.string.standalone_crash_message)
			.setCancelable(false)
			.setPositiveButton(R.string.standalone_crash_ok, (d, which) -> restartInto(createGameLaunchIntent()))
			.show();
		TextView message = dialog.findViewById(android.R.id.message);
		if (message != null) {
			message.setOnLongClickListener(v -> {
				// Stays landscape: rotating would recreate this screen as the plain dialog.
				dialog.dismiss();
				showCrashReport();
				return true;
			});
		}
	}

	/** Same path as tapping the app icon: boot splash, then straight into the game. */
	private Intent createGameLaunchIntent() {
		return new Intent(this, GameSettingsActivity.class)
			.setAction(Intent.ACTION_MAIN)
			.addCategory(Intent.CATEGORY_LAUNCHER);
	}

	private void writeCrashLog(String details) {
		try {
			File dir = new File(new File(getFilesDir(), "logs"), "crash");
			if (!dir.isDirectory() && !dir.mkdirs()) {
				return;
			}
			String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
			try (FileOutputStream output = new FileOutputStream(new File(dir, "crash-" + stamp + ".txt"))) {
				output.write(details.getBytes(StandardCharsets.UTF_8));
			}
			File[] logs = dir.listFiles((parent, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
			if (logs != null && logs.length > MAX_CRASH_LOGS) {
				Arrays.sort(logs, (a, b) -> a.getName().compareTo(b.getName()));
				for (int i = 0; i < logs.length - MAX_CRASH_LOGS; i++) {
					logs[i].delete();
				}
			}
		} catch (Exception exception) {
			Log.w("Sts2Crash", "Unable to save crash report.", exception);
		}
	}

	private void showCrashReport() {
		SystemBarInsetsHelper.enableEdgeToEdge(this);
		setContentView(R.layout.activity_sts2_crash);
		// Toolbar consumes top (status/cutout); content root consumes horizontal + bottom so children share one scaffold.
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(R.id.toolbar_crash), true, false, false, false);
		SystemBarInsetsHelper.applySystemBarPadding(findViewById(android.R.id.content), false, true, true, true);

		bindContent();
		bindActions();
	}

	private void bindContent() {
		((TextView) findViewById(R.id.text_crash_summary)).setText(buildCrashSummary());
		((TextView) findViewById(R.id.text_badge_renderer)).setText(getString(R.string.sts2_crash_badge_renderer, getRendererDisplayName()));
		((TextView) findViewById(R.id.text_badge_android)).setText(getString(R.string.sts2_crash_badge_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
		((TextView) findViewById(R.id.text_badge_build)).setText(getString(R.string.sts2_crash_badge_build, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE));
		((TextView) findViewById(R.id.text_crash_details)).setText(fullErrorDetails);
	}

	private void bindActions() {
		findViewById(R.id.button_open_settings).setOnClickListener(v -> restartInto(createSettingsIntent()));
		findViewById(R.id.button_retry_game).setOnClickListener(v -> restartInto(GodotApp.createLaunchIntent(this, true)));
		findViewById(R.id.button_copy_details).setOnClickListener(v -> copyErrorDetails());
		findViewById(R.id.button_close_app).setOnClickListener(v -> closeApplication());
	}

	private Intent createSettingsIntent() {
		Intent intent = new Intent(this, GameSettingsActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return intent;
	}

	private void restartInto(Intent intent) {
		if (config != null) {
			CustomActivityOnCrash.restartApplicationWithIntent(this, intent, config);
			return;
		}

		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		finish();
		startActivity(intent);
		killCurrentProcess();
	}

	private void closeApplication() {
		if (config != null) {
			CustomActivityOnCrash.closeApplication(this, config);
			return;
		}
		finish();
		killCurrentProcess();
	}

	private void copyErrorDetails() {
		ClipboardManager clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboardManager != null) {
			clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.sts2_crash_toolbar_title), fullErrorDetails));
			Snackbar.make(findViewById(android.R.id.content), R.string.sts2_crash_copied, Snackbar.LENGTH_SHORT).show();
		}
	}

	private String buildCrashSummary() {
		String stackTrace = CustomActivityOnCrash.getStackTraceFromIntent(getIntent());
		if (TextUtils.isEmpty(stackTrace)) {
			return getString(R.string.sts2_crash_summary_fallback);
		}

		String[] lines = stackTrace.replace("\r\n", "\n").split("\n");
		for (String line : lines) {
			if (!TextUtils.isEmpty(line.trim())) {
				return line.trim();
			}
		}
		return getString(R.string.sts2_crash_summary_fallback);
	}

	private String buildCompactErrorDetails() {
		String details = CustomActivityOnCrash.getAllErrorDetailsFromIntent(this, getIntent());
		if (TextUtils.isEmpty(details)) {
			details = getString(R.string.sts2_crash_detail_fallback);
		}
		return compactWhitespace(details);
	}

	private String compactWhitespace(String text) {
		String compacted = text.replace("\r\n", "\n");
		compacted = compacted.replace(" \n", "\n");
		while (compacted.contains("\n\n\n")) {
			compacted = compacted.replace("\n\n\n", "\n\n");
		}
		return compacted.trim();
	}

	private String getRendererDisplayName() {
		String renderer = RendererPreference.getSelectedRenderer(this);
		if (RendererPreference.RENDERER_OPENGL_ES3.equals(renderer)) {
			return getString(R.string.renderer_option_opengl_es3);
		}
		return getString(R.string.renderer_option_vulkan);
	}

	private static void killCurrentProcess() {
		android.os.Process.killProcess(android.os.Process.myPid());
		System.exit(10);
	}
}
