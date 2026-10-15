package dev.potplayer.handoff;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Explicit OTA installation. Android keeps the final install/unknown-source consent. */
public final class UpdateActivity extends AppCompatActivity {
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private TextView status;
  private MaterialButton install, check;
  private GitHubRelease release;
  private boolean busy, destroyed, permissionPending;
  private File pending;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    int space = (int) (24 * getResources().getDisplayMetrics().density);
    layout.setPadding(space, space * 2, space, space);
    TextView title = new TextView(this);
    title.setText("Обновления Синхро");
    title.setTextSize(28);
    layout.addView(title);
    status = new TextView(this);
    status.setTextSize(16);
    status.setPadding(0, space, 0, space);
    layout.addView(status);
    check = new MaterialButton(this);
    check.setText("Проверить GitHub Releases");
    layout.addView(check);
    install = new MaterialButton(this);
    install.setText("Скачать и установить");
    install.setEnabled(false);
    layout.addView(install);
    MaterialButton desktop = new MaterialButton(this);
    desktop.setText("Обновление ПК · открыть мастер");
    layout.addView(desktop);
    MaterialButton back = new MaterialButton(this);
    back.setText("Назад");
    layout.addView(back);
    setContentView(layout);
    check.setOnClickListener(v -> check());
    install.setOnClickListener(v -> download());
    back.setOnClickListener(v -> finish());
    desktop.setOnClickListener(
        v ->
            network.execute(
                () -> {
                  try {
                    ConnectionSettings.client(this).request("POST", "/api/setup/open", "{}");
                    show("Мастер обновления открыт на компьютере");
                  } catch (Exception error) {
                    show("Проверьте подключение к ПК");
                  }
                }));
    check();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (permissionPending) {
      permissionPending = false;
      if (getPackageManager().canRequestPackageInstalls()) launchInstall();
      else show("Разрешение не выдано. Нажмите «Скачать и установить», чтобы повторить.");
    }
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    network.shutdown();
    super.onDestroy();
  }

  private void show(String text) {
    runOnUiThread(
        () -> {
          if (!destroyed) status.setText(text);
        });
  }

  private void done() {
    runOnUiThread(
        () -> {
          if (!destroyed) {
            busy = false;
            check.setEnabled(true);
            install.setEnabled(release != null);
          }
        });
  }

  private HttpURLConnection connect(String url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    c.setRequestProperty("User-Agent", "Syncro-Android");
    if (c.getResponseCode() != 200) {
      c.disconnect();
      throw new IllegalStateException("GitHub unavailable");
    }
    if (!"https".equals(c.getURL().getProtocol())) {
      c.disconnect();
      throw new SecurityException("HTTPS required");
    }
    return c;
  }

  private static byte[] read(InputStream input, int limit) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) >= 0) {
      if (result.size() + count > limit) throw new IllegalArgumentException("Response too large");
      result.write(buffer, 0, count);
    }
    return result.toByteArray();
  }

  private void check() {
    if (busy) return;
    busy = true;
    release = null;
    pending = null;
    check.setEnabled(false);
    install.setEnabled(false);
    show("Проверяем стабильный релиз GitHub…");
    network.execute(
        () -> {
          try {
            String repo;
            try (InputStream input = getAssets().open("release-config.json")) {
              repo =
                  new JSONObject(new String(read(input, 4096), StandardCharsets.UTF_8))
                      .getString("android");
            }
            if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
              throw new IllegalArgumentException();
            HttpURLConnection c =
                connect("https://api.github.com/repos/" + repo + "/releases/latest");
            GitHubRelease latest;
            try (InputStream input = c.getInputStream()) {
              latest =
                  GitHubRelease.parse(
                      new JSONObject(new String(read(input, 1024 * 1024), StandardCharsets.UTF_8)),
                      repo);
            } finally {
              c.disconnect();
            }
            String current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (GitHubRelease.version(latest.tag) > GitHubRelease.version(current)) {
              release = latest;
              show("Доступна " + latest.tag + " · сейчас " + current);
            } else show("Установлена актуальная версия · " + current);
          } catch (Exception error) {
            show("Не удалось проверить релиз. Проверьте интернет и повторите.");
          } finally {
            done();
          }
        });
  }

  private void download() {
    if (busy || release == null) return;
    if (pending != null && pending.isFile()) {
      launchInstall();
      return;
    }
    busy = true;
    install.setEnabled(false);
    check.setEnabled(false);
    show("Скачиваем APK и проверяем SHA-256…");
    GitHubRelease selected = release;
    network.execute(
        () -> {
          File target = new File(getCacheDir(), "updates/Syncro-Android.apk");
          try {
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
              throw new IllegalStateException();
            HttpURLConnection c = connect(selected.url);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = c.getInputStream();
                FileOutputStream output = new FileOutputStream(target)) {
              byte[] buffer = new byte[65536];
              int count;
              while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > selected.size) throw new SecurityException();
                hash.update(buffer, 0, count);
                output.write(buffer, 0, count);
              }
            } finally {
              c.disconnect();
            }
            StringBuilder digest = new StringBuilder();
            for (byte b : hash.digest())
              digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (total != selected.size || !digest.toString().equals(selected.sha256))
              throw new SecurityException("Digest mismatch");
            verifyPackage(target, selected);
            pending = target;
            runOnUiThread(
                () -> {
                  if (!destroyed) launchInstall();
                });
          } catch (Exception error) {
            target.delete();
            pending = null;
            show("APK не прошёл проверку или не скачался. Текущая версия сохранена.");
          } finally {
            done();
          }
        });
  }

  @SuppressWarnings("deprecation")
  private void verifyPackage(File target, GitHubRelease selected) throws Exception {
    PackageManager pm = getPackageManager();
    int flags =
        android.os.Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageInfo candidate = pm.getPackageArchiveInfo(target.getAbsolutePath(), flags);
    PackageInfo current = pm.getPackageInfo(getPackageName(), flags);
    if (candidate == null
        || !getPackageName().equals(candidate.packageName)
        || GitHubRelease.version(candidate.versionName) != GitHubRelease.version(selected.tag)
        || (android.os.Build.VERSION.SDK_INT >= 28
            ? candidate.getLongVersionCode() <= current.getLongVersionCode()
            : candidate.versionCode <= current.versionCode))
      throw new SecurityException("Unexpected APK identity/version");
    android.content.pm.Signature[] a =
        android.os.Build.VERSION.SDK_INT >= 28
            ? candidate.signingInfo.getApkContentsSigners()
            : candidate.signatures;
    android.content.pm.Signature[] b =
        android.os.Build.VERSION.SDK_INT >= 28
            ? current.signingInfo.getApkContentsSigners()
            : current.signatures;
    if (a == null
        || b == null
        || a.length != 1
        || b.length != 1
        || !Arrays.equals(a[0].toByteArray(), b[0].toByteArray()))
      throw new SecurityException("APK signature changed");
  }

  private void launchInstall() {
    if (pending == null || !pending.isFile()) return;
    if (!getPackageManager().canRequestPackageInstalls()) {
      permissionPending = true;
      show("Разрешите Синхро устанавливать обновления, затем вернитесь сюда.");
      startActivity(
          new Intent(
              Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + getPackageName())));
      return;
    }
    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", pending);
    startActivity(
        new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    show("Подтвердите обновление в системном окне Android.");
  }
}
