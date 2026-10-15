package dev.potplayer.handoff;

import android.content.Context;
import android.content.SharedPreferences;

/** Retain the original preference file and application ID for seamless receiver upgrades. */
final class ConnectionSettings {
  static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
  }

  static ApiClient client(Context context) {
    SharedPreferences values = preferences(context);
    return new ApiClient(values.getString("server", ""), values.getString("token", ""));
  }
}
