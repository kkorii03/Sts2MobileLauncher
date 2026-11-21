package com.godot.game;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;

/**
 * Icon long-press shortcuts. They used to be static (manifest) shortcuts, which
 * cannot be hidden at runtime. The standalone build (game bundled in the APK)
 * must look like a regular game with no "settings" entry on the icon, so the
 * shortcuts are now published dynamically and only for the regular launcher.
 */
final class LauncherShortcuts {
	private static final String TAG = "Sts2Shortcuts";

	private LauncherShortcuts() {
	}

	static void update(Context context, boolean standalone) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
			return;
		}
		Context app = context.getApplicationContext();
		new Thread(() -> {
			try {
				ShortcutManager manager = app.getSystemService(ShortcutManager.class);
				if (manager == null) {
					return;
				}
				if (standalone) {
					manager.removeAllDynamicShortcuts();
					return;
				}
				manager.setDynamicShortcuts(Arrays.asList(
					// New ids: the old manifest ids stay reserved for shortcuts pinned before this change.
					build(app, "launch_game", 0, GodotApp.class, Icon.createWithResource(app, R.mipmap.icon),
						R.string.shortcut_launch_game_short, R.string.shortcut_launch_game_long),
					build(app, "open_settings", 1, GameSettingsActivity.class, Icon.createWithResource(app, R.drawable.ic_extra_settings_gear),
						R.string.shortcut_open_settings_short, R.string.shortcut_open_settings_long)
				));
			} catch (Exception exception) {
				Log.w(TAG, "Unable to update launcher shortcuts.", exception);
			}
		}, "sts2-launcher-shortcuts").start();
	}

	private static ShortcutInfo build(Context context, String id, int rank, Class<?> target, Icon icon, int shortLabel, int longLabel) {
		Intent intent = new Intent(Intent.ACTION_VIEW).setClassName(context.getPackageName(), target.getName());
		return new ShortcutInfo.Builder(context, id)
			.setIcon(icon)
			.setShortLabel(context.getString(shortLabel))
			.setLongLabel(context.getString(longLabel))
			.setIntent(intent)
			.setRank(rank)
			.build();
	}
}
