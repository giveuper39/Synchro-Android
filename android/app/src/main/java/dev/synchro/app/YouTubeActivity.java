package dev.synchro.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Official YouTube embed with native ownership control. The pairing key never enters the WebView.
 */
public final class YouTubeActivity extends AppCompatActivity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private ApiClient api;
  private WebView web;
  private FrameLayout outer;
  private TextView title, status;
  private MaterialButton reconnect;
  private String sourceId, sessionId, pageHtml;
  private YouTubeState last, pending;
  private long sequence, lastHeartbeat;
  private boolean visible, destroyed, pageLoaded, pageBlank, takingOver, sending, revoked;
  private View fullScreen;
  private WebChromeClient.CustomViewCallback fullScreenCallback;
  private int previousOrientation;
  private int shownError;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    buildUi();
    try {
      api = ConnectionSettings.client(this);
      try (InputStream input = getAssets().open("youtube-player.html");
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        pageHtml = output.toString(StandardCharsets.UTF_8.name());
      }
      sourceId = getIntent().getStringExtra("sourceId");
      if (sourceId == null || sourceId.length() > 256) throw new IllegalArgumentException();
      takeover();
    } catch (Exception error) {
      status.setText("Подключите компьютер и выберите страницу YouTube на главном экране");
    }
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (fullScreen != null) hideFullScreen();
                else finish();
              }
            });
  }

  private int dp(int value) {
    return AppDesign.dp(this, value);
  }

  @SuppressLint("SetJavaScriptEnabled") // Our own document hosts the official YouTube IFrame API.
  private void buildUi() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    outer = new FrameLayout(this);
    outer.setBackgroundColor(AppDesign.BACKGROUND);
    LinearLayout body = AppDesign.column(this);
    outer.addView(body, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(12), dp(6), dp(16), dp(6));
    body.addView(header, new LinearLayout.LayoutParams(-1, dp(62)));
    ImageButton back = new ImageButton(this);
    back.setImageResource(R.drawable.ic_back);
    back.setImageTintList(android.content.res.ColorStateList.valueOf(AppDesign.TEXT));
    back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    back.setContentDescription("Назад");
    back.setOnClickListener(v -> finish());
    header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
    TextView label = AppDesign.text(this, "YouTube", 17, AppDesign.TEXT);
    header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    TextView brand = AppDesign.text(this, "Synchro", 13, AppDesign.AMBER);
    header.addView(brand);
    web = new WebView(this);
    web.setBackgroundColor(0xff08090c);
    int height = Math.round(getResources().getDisplayMetrics().widthPixels * 9f / 16f);
    body.addView(web, new LinearLayout.LayoutParams(-1, height));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setMediaPlaybackRequiresUserGesture(
        false); // The user explicitly selected Continue in Synchro.
    settings.setSupportMultipleWindows(false);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageFinished(WebView view, String url) {
            if (destroyed || "about:blank".equals(url) || last == null) return;
            pageLoaded = true;
            try {
              JSONObject value = last.video().put("autoplay", !pageBlank && visible && !revoked);
              view.evaluateJavascript(
                  "window.SynchroYouTube&&window.SynchroYouTube.load(" + value + ")", null);
              pageBlank = false;
            } catch (Exception ignored) {
              status.setText("Не удалось подготовить плеер YouTube");
            }
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if ("about:blank".equals(url)) return false;
            // External navigation never receives Authorization headers or a native JavaScript
            // interface.
            if (api != null && url.equals(api.server() + "/youtube/player/")) return false;
            return true;
          }
        });
    web.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullScreen != null) {
              callback.onCustomViewHidden();
              return;
            }
            fullScreen = view;
            fullScreenCallback = callback;
            previousOrientation = getRequestedOrientation();
            outer.addView(view, new FrameLayout.LayoutParams(-1, -1));
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            WindowInsetsControllerCompat bars =
                new WindowInsetsControllerCompat(getWindow(), outer);
            bars.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsetsCompat.Type.systemBars());
          }

          @Override
          public void onHideCustomView() {
            hideFullScreen();
          }
        });
    ScrollView scroll = new ScrollView(this);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout context = AppDesign.column(this);
    context.setPadding(dp(22), dp(22), dp(22), dp(26));
    scroll.addView(context);
    TextView eyebrow = AppDesign.text(this, "С КОМПЬЮТЕРА НА ТЕЛЕФОН", 10, AppDesign.AMBER);
    eyebrow.setLetterSpacing(.12f);
    context.addView(eyebrow);
    title = AppDesign.text(this, "Продолжите просмотр", 23, AppDesign.TEXT);
    title.setPadding(0, dp(10), 0, dp(14));
    context.addView(title);
    status = AppDesign.text(this, "Ставим выбранную страницу на паузу…", 13, AppDesign.MUTED);
    status.setPadding(0, 0, 0, dp(20));
    context.addView(status);
    LinearLayout card = AppDesign.column(this);
    card.setBackground(AppDesign.background(this, AppDesign.SURFACE, 20));
    card.setPadding(dp(18), dp(18), dp(18), dp(18));
    context.addView(card);
    card.addView(AppDesign.text(this, "Один просмотр. Два устройства.", 14, AppDesign.TEXT));
    TextView note =
        AppDesign.text(
            this,
            "Пауза здесь возвращает позицию на ПК. Громкость регулируется кнопками телефона. При"
                + " выходе YouTube остаётся на паузе.",
            12,
            AppDesign.MUTED);
    note.setPadding(0, dp(8), 0, dp(8));
    card.addView(note);
    reconnect = AppDesign.button(this, "Подключить страницу снова", true);
    reconnect.setVisibility(View.GONE);
    reconnect.setOnClickListener(v -> takeover());
    card.addView(reconnect, new LinearLayout.LayoutParams(-1, dp(54)));
    MaterialButton external = AppDesign.button(this, "Открыть в YouTube", false);
    external.setOnClickListener(v -> externalPlayer());
    LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, dp(54));
    button.topMargin = dp(12);
    context.addView(external, button);
    setContentView(outer);
    ViewCompat.setOnApplyWindowInsetsListener(
        body,
        (view, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
  }

  private void takeover() {
    if (api == null || sourceId == null || takingOver || destroyed) return;
    takingOver = true;
    reconnect.setEnabled(false);
    status.setText("Подключаем выбранную страницу YouTube…");
    network.execute(
        () -> {
          try {
            JSONObject result =
                api.request(
                    "POST",
                    "/api/youtube/takeover",
                    new JSONObject().put("sourceId", sourceId).toString());
            String id = result.getString("sessionId");
            YouTubeState state = YouTubeState.session(result);
            main.post(
                () -> {
                  if (destroyed) return;
                  sessionId = id;
                  sequence = 0;
                  pending = null;
                  last = state;
                  revoked = false;
                  takingOver = false;
                  reconnect.setEnabled(true);
                  reconnect.setVisibility(View.GONE);
                  title.setText(state.title);
                  status.setText("Компьютер на паузе · загружаем YouTube");
                  loadPage(false);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (destroyed) return;
                  takingOver = false;
                  reconnect.setEnabled(true);
                  reconnect.setVisibility(View.VISIBLE);
                  status.setText(
                      error instanceof ApiClient.ApiException
                          ? error.getMessage()
                          : "Компьютер недоступен. Проверьте Wi-Fi и сервис Synchro");
                });
          }
        });
  }

  private void loadPage(boolean paused) {
    shownError = 0;
    pageBlank = paused;
    pageLoaded = false;
    web.loadDataWithBaseURL(
        "https://" + getPackageName() + "/youtube/player/", pageHtml, "text/html", "UTF-8", null);
  }

  private void hideFullScreen() {
    if (fullScreen == null) return;
    outer.removeView(fullScreen);
    fullScreen = null;
    WebChromeClient.CustomViewCallback callback = fullScreenCallback;
    fullScreenCallback = null;
    if (callback != null) callback.onCustomViewHidden();
    setRequestedOrientation(previousOrientation);
    new WindowInsetsControllerCompat(getWindow(), outer).show(WindowInsetsCompat.Type.systemBars());
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (!visible || destroyed) return;
          if (pageLoaded)
            web.evaluateJavascript(
                "JSON.stringify(window.SynchroYouTube&&window.SynchroYouTube.snapshot())",
                value -> snapshot(value, false));
          if (sessionId != null
              && last != null
              && !revoked
              && SystemClock.elapsedRealtime() - lastHeartbeat >= 2000) {
            lastHeartbeat = SystemClock.elapsedRealtime();
            refreshOwnership();
            queueProgress(last, false);
          }
          main.postDelayed(this, 500);
        }
      };

  private void snapshot(String encoded, boolean checkpoint) {
    try {
      YouTubeState current = YouTubeState.javascript(encoded);
      if (current.errorCode != 0) {
        playerError(current.errorCode);
        return;
      }
      if (!current.ready || destroyed || revoked) return;
      boolean changed =
          last == null || current.paused != last.paused || !current.videoId.equals(last.videoId);
      last = checkpoint ? current.paused() : current;
      title.setText(current.title);
      status.setText(
          last.paused
              ? "На паузе · позиция возвращается на ПК"
              : "Смотрим на телефоне · компьютер на паузе");
      if (last.paused) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      if (changed || checkpoint) queueProgress(last, true);
    } catch (Exception ignored) {
      /* Ignore a not-yet-created own-page namespace. */
    }
  }

  private void queueProgress(YouTubeState value, boolean urgent) {
    if (sessionId == null || revoked || !value.ready || destroyed) return;
    if (sending) {
      pending = value;
      return;
    }
    if (!urgent && SystemClock.elapsedRealtime() - lastHeartbeat > 5000) return;
    sending = true;
    String id = sessionId;
    long event = ++sequence;
    network.execute(
        () -> {
          Exception failure = sendProgress(value, id, event);
          main.post(
              () -> {
                sending = false;
                if (destroyed || !id.equals(sessionId)) return;
                if (failure instanceof ApiClient.ApiException
                    && ((ApiClient.ApiException) failure).status == 409) revoke();
                else if (failure instanceof ApiClient.ApiException
                    && ((ApiClient.ApiException) failure).status == 400) {
                  revoke();
                  status.setText(
                      "Источник не поддерживает этот переход. Подключите нужный ролик на ПК"
                          + " заново");
                } else if (failure != null)
                  status.setText("Позиция ещё не синхронизирована · повторяем подключение");
                YouTubeState next = pending;
                pending = null;
                if (next != null && !revoked) queueProgress(next, true);
              });
        });
  }

  private Exception sendProgress(YouTubeState value, String id, long event) {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        api.request("POST", "/api/youtube/progress", value.progress(id, event).toString());
        return null;
      } catch (Exception error) {
        if (attempt != 0
            || error instanceof org.json.JSONException
            || error instanceof ApiClient.ApiException
                && !((ApiClient.ApiException) error).retryable()) return error;
        try {
          Thread.sleep(250);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return interrupted;
        }
      }
    }
    return null;
  }

  private void refreshOwnership() {
    if (sending || takingOver) return;
    String id = sessionId;
    network.execute(
        () -> {
          try {
            JSONObject current = api.request("GET", "/api/youtube/session", null);
            boolean ended =
                !id.equals(current.optString("sessionId")) || current.optBoolean("revoked");
            if (ended)
              main.post(
                  () -> {
                    if (id.equals(sessionId)) revoke();
                  });
          } catch (Exception ignored) {
            /* Progress reports a useful connection error and keeps its last position. */
          }
        });
  }

  private void revoke() {
    if (destroyed || revoked) return;
    revoked = true;
    pending = null;
    web.evaluateJavascript("window.SynchroYouTube&&window.SynchroYouTube.pause()", null);
    status.setText("Просмотр изменён на ПК. Подключите страницу снова, чтобы продолжить здесь");
    reconnect.setVisibility(View.VISIBLE);
  }

  private void playerError(int code) {
    if (shownError == code) return;
    shownError = code;
    if (last != null && !revoked) queueProgress(last.paused(), true);
    String detail =
        code == 100
            ? "Ролик недоступен или удалён"
            : code == 101 || code == 150
                ? "Автор запретил просмотр этого ролика во встроенном плеере"
                : code == 153
                    ? "YouTube не подтвердил источник встроенного плеера"
                    : code >= 900
                        ? "Не удалось подключиться к YouTube. Проверьте доступ в интернет"
                        : "YouTube не смог загрузить ролик";
    status.setText(
        detail + ". Можно открыть YouTube отдельно; автоматическая синхронизация там недоступна");
    reconnect.setText("Попробовать снова");
    reconnect.setVisibility(View.VISIBLE);
  }

  private void checkpoint() {
    if (last == null || sessionId == null || revoked) return;
    last = last.paused();
    queueProgress(last, true);
    if (pageLoaded)
      web.evaluateJavascript(
          "JSON.stringify(window.SynchroYouTube&&window.SynchroYouTube.pause())",
          value -> snapshot(value, true));
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private void externalPlayer() {
    if (last == null) return;
    new AlertDialog.Builder(this)
        .setTitle("Открыть YouTube отдельно?")
        .setMessage(
            "Сохраним текущую позицию на ПК и оставим его на паузе. Просмотр в другом приложении"
                + " автоматически не синхронизируется.")
        .setNegativeButton("Остаться", null)
        .setPositiveButton(
            "Открыть",
            (dialog, which) -> {
              checkpoint();
              String address =
                  "https://www.youtube.com/watch?v="
                      + last.videoId
                      + "&t="
                      + last.positionMs / 1000
                      + "s";
              try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
              } catch (android.content.ActivityNotFoundException error) {
                status.setText("Не найдено приложение для открытия YouTube");
              }
            })
        .show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    visible = true;
    web.onResume();
    if (pageBlank && last != null && sessionId != null) loadPage(true);
    main.removeCallbacks(tick);
    main.post(tick);
  }

  @Override
  protected void onPause() {
    visible = false;
    main.removeCallbacks(tick);
    checkpoint();
    web.onPause();
    super.onPause();
  }

  @Override
  protected void onStop() {
    // A normal YouTube embed must not become a hidden audio background service.
    pageLoaded = false;
    pageBlank = true;
    web.loadUrl("about:blank");
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (pending != null && sessionId != null && api != null && !revoked) {
      YouTubeState finalState = pending.paused();
      String id = sessionId;
      long event = ++sequence;
      network.execute(() -> sendProgress(finalState, id, event));
      pending = null;
    }
    destroyed = true;
    main.removeCallbacksAndMessages(null);
    network.shutdown();
    hideFullScreen();
    ((ViewGroup) web.getParent()).removeView(web);
    web.destroy();
    super.onDestroy();
  }
}
