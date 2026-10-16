package dev.synchro.app;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.Collections;

/** Native Android mini-player; no overlay permission, background activity or second player. */
final class PipController {
  private final AppCompatActivity activity;
  private final String action;
  private final BroadcastReceiver receiver;
  private PictureInPictureParams params;
  private boolean enabled, paused = true;

  PipController(AppCompatActivity activity, Runnable toggle) {
    this.activity = activity;
    action = activity.getPackageName() + ".PIP_PLAY_PAUSE";
    receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            if (action.equals(intent.getAction()) && activity.isInPictureInPictureMode())
              toggle.run();
          }
        };
    ContextCompat.registerReceiver(
        activity, receiver, new IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED);
    activity
        .findViewById(R.id.video)
        .addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> rebuild());
  }

  void update(boolean enabled, boolean paused) {
    if (this.enabled == enabled && this.paused == paused && params != null) return;
    this.enabled = enabled;
    this.paused = paused;
    rebuild();
  }

  private void rebuild() {
    if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
      return;
    Intent intent = new Intent(action).setPackage(activity.getPackageName());
    PendingIntent pending =
        PendingIntent.getBroadcast(
            activity, 40, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String label = paused ? "Продолжить" : "Пауза";
    RemoteAction control =
        new RemoteAction(
            Icon.createWithResource(activity, paused ? R.drawable.ic_play : R.drawable.ic_pause),
            label,
            label,
            pending);
    Rect rect = new Rect();
    activity.findViewById(R.id.video).getGlobalVisibleRect(rect);
    PictureInPictureParams.Builder builder =
        new PictureInPictureParams.Builder()
            .setAspectRatio(new Rational(16, 9))
            .setActions(Collections.singletonList(control));
    if (!rect.isEmpty()) builder.setSourceRectHint(rect);
    if (Build.VERSION.SDK_INT >= 31)
      builder.setAutoEnterEnabled(enabled && !paused).setSeamlessResizeEnabled(true);
    params = builder.build();
    activity.setPictureInPictureParams(params);
  }

  boolean enter() {
    if (!enabled || params == null) return false;
    try {
      return activity.enterPictureInPictureMode(params);
    } catch (IllegalStateException | IllegalArgumentException error) {
      return false;
    }
  }

  void leaving() {
    if (Build.VERSION.SDK_INT < 31 && enabled && !paused) enter();
  }

  void close() {
    activity.unregisterReceiver(receiver);
  }
}
