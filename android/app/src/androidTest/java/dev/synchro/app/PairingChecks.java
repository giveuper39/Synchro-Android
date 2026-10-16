package dev.synchro.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.URI;

/** Real receiver Activities against the isolated synthetic connection fixture only. */
final class PairingChecks {
  static void run(Instrumentation host, Bundle args) {
    Bundle result = new Bundle();
    Activity activity = null;
    try {
      String server = args.getString("server"), secret = args.getString("token");
      if (new URI(server).getPort() != 18765) throw new IllegalArgumentException("Fixture only");
      String link =
          new ApiClient(server, secret).request("GET", "/local/pairing", null).getString("url");
      ConnectionSettings.preferences(host.getTargetContext())
          .edit()
          .remove("server")
          .remove("token")
          .commit();
      Intent camera =
          new Intent(Intent.ACTION_VIEW, Uri.parse(link))
              .setClassName(host.getTargetContext(), MainActivity.class.getName())
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      activity = host.startActivitySync(camera);
      awaitSaved(host, server, secret);
      Activity first = activity;
      host.runOnMainSync(first::finish);
      ConnectionSettings.preferences(host.getTargetContext())
          .edit()
          .remove("server")
          .remove("token")
          .commit();

      PairingCode code = PairingCode.parse(link);
      String query =
          new Uri.Builder()
              .appendQueryParameter("v", "1")
              .appendQueryParameter("server", code.server)
              .appendQueryParameter("token", code.token)
              .appendQueryParameter("name", code.name)
              .build()
              .getEncodedQuery();
      String intentUri =
          "intent://pair?"
              + query
              + "#Intent;scheme=syncro;package=dev.potplayer.handoff;S.browser_fallback_url="
              + Uri.encode(link + "&install=1")
              + ";end";
      Intent browser = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
      browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      if (browser.resolveActivity(host.getTargetContext().getPackageManager()) == null)
        throw new AssertionError("Browser intent does not resolve to installed receiver");
      activity = host.startActivitySync(browser);
      awaitSaved(host, server, secret);
      result.putBoolean("cameraUrlConnects", true);
      result.putBoolean("browserIntentOpensInstalledApp", true);
      result.putString("result", "passed");
      Activity last = activity;
      host.runOnMainSync(last::finish);
      host.finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("error", error.getClass().getSimpleName());
      if (activity != null) {
        Activity last = activity;
        host.runOnMainSync(last::finish);
      }
      host.finish(Activity.RESULT_CANCELED, result);
    }
  }

  private static void awaitSaved(Instrumentation host, String server, String secret) {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    while (SystemClock.elapsedRealtime() < deadline) {
      android.content.SharedPreferences preferences =
          ConnectionSettings.preferences(host.getTargetContext());
      if (server.equals(preferences.getString("server", ""))
          && secret.equals(preferences.getString("token", ""))) return;
      SystemClock.sleep(30);
    }
    throw new AssertionError("Receiver did not save the supplied connection");
  }
}
