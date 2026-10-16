package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Actual native share sheet, clipboard send and bidirectional link inbox against a fake PC. */
final class SharingChecks {
  private final Instrumentation host;
  private final Bundle args;
  private Activity activity;

  SharingChecks(Instrumentation host, Bundle args) {
    this.host = host;
    this.args = args;
  }

  private View find(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = find(((ViewGroup) view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private void click(String text) {
    host.runOnMainSync(
        () -> {
          View view = find(activity.getWindow().getDecorView(), text);
          if (view == null) throw new AssertionError("Missing button " + text);
          view.performClick();
        });
  }

  private boolean has(String text) {
    AtomicBoolean result = new AtomicBoolean();
    host.runOnMainSync(() -> result.set(find(activity.getWindow().getDecorView(), text) != null));
    return result.get();
  }

  private void await(String text) {
    long deadline = SystemClock.elapsedRealtime() + 8000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (has(text)) return;
      SystemClock.sleep(50);
    }
    throw new AssertionError("Missing state " + text);
  }

  void run() {
    Bundle result = new Bundle();
    try {
      String server = args.getString("server"), token = args.getString("token");
      if (new java.net.URI(server).getPort() != 18765)
        throw new IllegalArgumentException("Use fixture only");
      ConnectionSettings.preferences(host.getTargetContext())
          .edit()
          .putString("server", server)
          .putString("token", token)
          .commit();
      ApiClient api = new ApiClient(server, token);
      activity =
          host.startActivitySync(
              new Intent(Intent.ACTION_SEND)
                  .setClassName(host.getTargetContext(), SyncActivity.class.getName())
                  .setType("text/plain")
                  .putExtra(Intent.EXTRA_TEXT, "Synchro · последнее копирование")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      click("Скопировать на ПК");
      await("Скопировано на ПК · без записи в историю Windows");
      if (!new JSONObject(api.request("GET", "/api/clipboard", null).toString())
          .getString("text")
          .equals("Synchro · последнее копирование"))
        throw new AssertionError("Clipboard mismatch");
      Field field = SyncActivity.class.getDeclaredField("text");
      field.setAccessible(true);
      EditText text = (EditText) field.get(activity);
      AtomicBoolean cleared = new AtomicBoolean();
      host.runOnMainSync(() -> cleared.set(text.getText().length() == 0));
      if (!cleared.get()) throw new AssertionError("Sent text retained in UI");
      result.putBoolean("shareTextAndClear", true);
      host.runOnMainSync(activity::finish);
      activity =
          host.startActivitySync(
              new Intent(Intent.ACTION_SEND)
                  .setClassName(host.getTargetContext(), SyncActivity.class.getName())
                  .setType("text/plain")
                  .putExtra(Intent.EXTRA_TEXT, "https://example.org/from-opera")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      click("Открыть в Chrome на ПК");
      await("Вкладка открыта в Chrome на ПК");
      result.putBoolean("operaToChrome", true);
      api.request(
          "POST",
          "/api/links",
          new JSONObject()
              .put("target", "phone")
              .put("url", "https://example.org/from-chrome")
              .put("title", "Тест вкладки с ПК")
              .put("requestId", "native-share-test")
              .toString());
      await("Тест вкладки с ПК");
      final Intent[] opened = new Intent[1];
      Instrumentation.ActivityMonitor monitor =
          new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
              if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                opened[0] = intent;
                return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
              }
              return null;
            }
          };
      host.addMonitor(monitor);
      click("Открыть в Opera  →");
      SystemClock.sleep(600);
      host.removeMonitor(monitor);
      if (opened[0] == null || !"https://example.org/from-chrome".equals(opened[0].getDataString()))
        throw new AssertionError("Browser link mismatch");
      if (api.request("GET", "/api/sync", null).getJSONArray("links").length() != 0)
        throw new AssertionError("Opened inbox item not acknowledged");
      result.putBoolean("chromeToPhoneAndConsume", true);
      result.putString("result", "passed");
      host.runOnMainSync(activity::finish);
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      host.finish(Activity.RESULT_CANCELED, result);
    }
  }
}
