package com.sanduo.pdaregistry;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

/** Uses the launcher's supported pin flow instead of the obsolete install-shortcut broadcast. */
public final class HomeShortcut {
  private static final String ID = "pda-registry-home";

  private HomeShortcut() {}

  public static ShortcutInfo info(Context context) {
    Intent launch =
        new Intent(Intent.ACTION_MAIN)
            .setClass(context, MainActivity.class)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return new ShortcutInfo.Builder(context, ID)
        .setShortLabel(context.getString(R.string.app_name))
        .setLongLabel(context.getString(R.string.app_name))
        .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(launch)
        .build();
  }

  public static boolean isPinned(Context context) {
    ShortcutManager manager = context.getSystemService(ShortcutManager.class);
    if (manager == null) return false;
    for (ShortcutInfo item : manager.getPinnedShortcuts()) if (ID.equals(item.getId())) return true;
    return false;
  }

  public static boolean request(Context context) {
    ShortcutManager manager = context.getSystemService(ShortcutManager.class);
    return manager != null
        && manager.isRequestPinShortcutSupported()
        && manager.requestPinShortcut(info(context), null);
  }
}
