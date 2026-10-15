package dev.potplayer.handoff;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in network smoke check against the public stable release; never installs an APK. */
final class UpdateChecks {
  static void run(Instrumentation host) {
    Bundle result = new Bundle();
    try {
      Activity activity =
          host.startActivitySync(
              new Intent()
                  .setClassName(host.getTargetContext(), UpdateActivity.class.getName())
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      AtomicReference<String> text = new AtomicReference<>("");
      long deadline = SystemClock.elapsedRealtime() + 45000;
      while (SystemClock.elapsedRealtime() < deadline) {
        host.runOnMainSync(() -> text.set(text(activity.getWindow().getDecorView())));
        if (text.get().contains("Установлена актуальная версия")
            || text.get().contains("Доступна v")) break;
        if (text.get().contains("Не удалось проверить"))
          throw new AssertionError("Native GitHub release check failed");
        SystemClock.sleep(100);
      }
      if (!(text.get().contains("Установлена актуальная версия")
          || text.get().contains("Доступна v")))
        throw new AssertionError("Native release check timed out");
      result.putBoolean("nativeGitHubStableReleaseCheck", true);
      result.putString("result", "passed");
      host.runOnMainSync(activity::finish);
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.toString());
      host.finish(Activity.RESULT_CANCELED, result);
    }
  }

  private static String text(View view) {
    StringBuilder result = new StringBuilder();
    if (view instanceof TextView) result.append(((TextView) view).getText());
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        result.append(text(((ViewGroup) view).getChildAt(i)));
    return result.toString();
  }
}
