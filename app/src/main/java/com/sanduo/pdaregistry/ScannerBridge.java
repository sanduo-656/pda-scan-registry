package com.sanduo.pdaregistry;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

public final class ScannerBridge {
  public static final String DEFAULT_ACTION = "com.service.scanner.data", DEFAULT_KEY = "ScanCode";

  public interface Listener {
    void scanned(String value);
  }

  private final Context context;
  private final Listener listener;
  private boolean registered;
  private String key = DEFAULT_KEY;
  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
          try {
            String code = intent.getStringExtra(key);
            if (code != null) listener.scanned(code);
          } catch (android.os.BadParcelableException | ClassCastException ignored) {
          }
        }
      };

  public ScannerBridge(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  public void start(String action, String key) {
    stop();
    this.key = key;
    IntentFilter filter = new IntentFilter(action);
    if (Build.VERSION.SDK_INT >= 33)
      context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    else context.registerReceiver(receiver, filter);
    registered = true;
  }

  public void stop() {
    if (registered) {
      context.unregisterReceiver(receiver);
      registered = false;
    }
  }

  public void enableBroadcastOutput() {
    Intent config = new Intent("com.service.scanner.broadcast.output");
    config.setComponent(
        new ComponentName(
            "com.hikrobotics.pdaservice", "com.pda.service.broadcast.ServiceControlReceiver"));
    config.putExtra("broadcastOutput", true);
    context.sendBroadcast(config);
  }
}
