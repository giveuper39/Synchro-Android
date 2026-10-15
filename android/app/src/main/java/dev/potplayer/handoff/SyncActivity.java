package dev.potplayer.handoff;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.InputFilter;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit sharing UI plus the state of the optional system-clipboard bridge. No text history. */
public final class SyncActivity extends AppCompatActivity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private ApiClient api;
  private LinearLayout clipboardPanel, tabsPanel, inbox;
  private TextView status, autoStatus;
  private EditText text, link;
  private SwitchMaterial automatic;
  private boolean refreshing, visible, destroyed, settingMode, updatingSwitch, sending;
  private String sharedTitle = "", requestId = UUID.randomUUID().toString();
  private String renderedInbox = "";

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    buildUi();
    try {
      api = ConnectionSettings.client(this);
    } catch (IllegalArgumentException error) {
      status.setText("Сначала подключите ПК по QR-коду на главном экране");
    }
    receive(getIntent());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    receive(intent);
  }

  @Override
  protected void onResume() {
    super.onResume();
    visible = true;
    refresh();
    main.postDelayed(poll, 2000);
  }

  @Override
  protected void onPause() {
    visible = false;
    main.removeCallbacks(poll);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    main.removeCallbacksAndMessages(null);
    network.shutdown();
    super.onDestroy();
  }

  private void receive(Intent intent) {
    CharSequence supplied = null;
    if (Intent.ACTION_SEND.equals(intent.getAction()))
      supplied = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction()))
      supplied = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
    String value = supplied == null ? "" : supplied.toString();
    if (value.length() > 16000) {
      status.setText("Текст слишком большой: до 16 000 символов");
      value = "";
    }
    if (!value.isEmpty()) {
      text.setText(value);
      String url = SharedLink.fromText(value);
      if (url != null) link.setText(url);
      sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT);
      if (sharedTitle == null) sharedTitle = "";
      select(url != null);
    } else select("tabs".equals(intent.getStringExtra("section")));
    requestId = UUID.randomUUID().toString();
  }

  private void buildUi() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(0xff111214);
    LinearLayout root = column();
    root.setPadding(dp(24), dp(30), dp(24), dp(24));
    scroll.addView(root);
    setContentView(scroll);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          androidx.core.graphics.Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(
              dp(24) + bars.left, dp(16) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
          return insets;
        });
    MaterialButton back = button("‹  Синхро", root, () -> finish());
    back.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
    back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff111214));
    back.setTextColor(0xfff6f5ef);
    TextView heading = label("Между телефоном\nи компьютером", 29, 0xfff6f5ef);
    heading.setTypeface(null, Typeface.BOLD);
    root.addView(heading);
    TextView caption = label("Локальная сеть · ваши устройства", 13, 0xffa4a7ad);
    caption.setPadding(0, dp(10), 0, dp(20));
    root.addView(caption);
    LinearLayout choices = new LinearLayout(this);
    root.addView(choices);
    MaterialButton clip = button("Буфер обмена", choices, () -> select(false));
    MaterialButton tabs = button("Вкладки", choices, () -> select(true));
    clip.setLayoutParams(new LinearLayout.LayoutParams(0, dp(54), 1));
    tabs.setLayoutParams(new LinearLayout.LayoutParams(0, dp(54), 1));
    status = label("Подключаемся к ПК…", 13, 0xffa4a7ad);
    status.setPadding(0, dp(14), 0, dp(10));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status);
    clipboardPanel = column();
    root.addView(clipboardPanel);
    LinearLayout auto = card(clipboardPanel);
    automatic = new SwitchMaterial(this);
    automatic.setText("Автосинхронизация");
    automatic.setTextColor(Color.WHITE);
    automatic.setTextSize(18);
    automatic.setId(View.generateViewId());
    auto.addView(automatic);
    autoStatus =
        label(
            "Последнее копирование · в обе стороны\nИстория в Синхро не сохраняется",
            13,
            0xffa4a7ad);
    autoStatus.setPadding(0, dp(8), 0, dp(8));
    auto.addView(autoStatus);
    button(
        "Настроить по QR на компьютере",
        auto,
        () ->
            submit(
                "POST",
                "/api/setup/open",
                body("open", true),
                result -> status.setText("Мастер с QR-кодом открыт на компьютере")));
    button("Как это работает", auto, this::setup);
    automatic.setOnCheckedChangeListener(
        (view, enabled) -> {
          if (!updatingSwitch) mode(enabled);
        });
    LinearLayout manual = card(clipboardPanel);
    manual.addView(label("Передать конкретный текст", 20, Color.WHITE));
    text = input(manual, "Текст для передачи", true);
    button(
        "Вставить последнее с телефона",
        manual,
        () -> {
          ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          ClipData data = clipboard.getPrimaryClip();
          if (data != null && data.getItemCount() > 0 && data.getItemAt(0).getText() != null)
            text.setText(data.getItemAt(0).getText());
        });
    button(
        "Скопировать на ПК",
        manual,
        () -> {
          String value = text.getText().toString();
          if (value.isEmpty()) {
            status.setText("Сначала вставьте или введите текст");
            return;
          }
          submit(
              "POST",
              "/api/clipboard",
              body("text", value),
              result -> {
                text.setText("");
                status.setText("Скопировано на ПК · без записи в историю Windows");
              });
        });
    button(
        "Получить текущий буфер ПК",
        manual,
        () ->
            submit(
                "GET",
                "/api/clipboard",
                null,
                result -> {
                  ClipData clipData = ClipData.newPlainText("Синхро", result.optString("text"));
                  PersistableBundle extras = new PersistableBundle();
                  extras.putString("dev.syncro.clipboard.origin", result.optString("origin"));
                  extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                  clipData.getDescription().setExtras(extras);
                  ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clipData);
                  status.setText("Текущий текст ПК скопирован на телефон");
                }));
    tabsPanel = column();
    root.addView(tabsPanel);
    LinearLayout composer = card(tabsPanel);
    composer.addView(label("Передать вкладку на ПК", 20, Color.WHITE));
    TextView swipe =
        label(
            "Из Opera: Поделиться → Синхро\nПроведите здесь вправо, чтобы открыть в Chrome  →",
            13,
            0xffc9bf99);
    swipe.setPadding(0, dp(14), 0, dp(12));
    composer.addView(swipe);
    swipe(swipe, this::sendLink);
    link = input(composer, "https://…", false);
    button("Открыть в Chrome на ПК", composer, this::sendLink);
    TextView incoming = label("С компьютера", 21, Color.WHITE);
    incoming.setPadding(0, dp(24), 0, dp(10));
    tabsPanel.addView(incoming);
    inbox = column();
    tabsPanel.addView(inbox);
    TextView help =
        label(
            "В Chrome откройте расширение «Синхро» и отправьте текущую вкладку. Здесь её можно"
                + " открыть свайпом вправо.",
            13,
            0xffa4a7ad);
    help.setPadding(0, dp(18), 0, dp(14));
    tabsPanel.addView(help);
    button(
        "Открыть ссылку из буфера в Opera",
        tabsPanel,
        () -> {
          ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          ClipData data = clipboard.getPrimaryClip();
          String value =
              data != null && data.getItemCount() > 0 && data.getItemAt(0).getText() != null
                  ? SharedLink.fromText(data.getItemAt(0).getText().toString())
                  : null;
          if (value == null) status.setText("В текущем буфере нет веб-ссылки");
          else {
            JSONObject item = body("url", value);
            put(item, "title", "Ссылка из буфера");
            open(item);
          }
        });
  }

  private void select(boolean tabs) {
    clipboardPanel.setVisibility(tabs ? View.GONE : View.VISIBLE);
    tabsPanel.setVisibility(tabs ? View.VISIBLE : View.GONE);
  }

  private void setup() {
    new AlertDialog.Builder(this)
        .setTitle("Системный буфер Android")
        .setMessage(
            "Для фонового режима нужен один раз разрешённый ADB-доступ к телефону. Root не"
                + " нужен.\n\n"
                + "1. Нажмите «Настроить по QR на компьютере». В настройках беспроводной отладки"
                + " выберите сопряжение по QR и отсканируйте код мастера. Порты вводить не"
                + " нужно.\n\n"
                + "2. Включите автосинхронизацию здесь. После перезагрузки телефона может"
                + " понадобиться снова включить беспроводную отладку.\n\n"
                + "В Синхро хранится только настройка подключения; текст не записывается на диск.")
        .setPositiveButton("Понятно", null)
        .show();
  }

  private void mode(boolean enabled) {
    if (settingMode) return;
    settingMode = true;
    submit(
        "POST",
        "/api/clipboard/mode",
        body("enabled", enabled),
        result -> {
          settingMode = false;
          render(result);
        });
  }

  private void sendLink() {
    if (sending) return;
    final String url;
    try {
      url = SharedLink.validate(link.getText().toString().trim());
    } catch (IllegalArgumentException error) {
      status.setText(error.getMessage());
      return;
    }
    sending = true;
    JSONObject value = body("url", url);
    put(value, "title", sharedTitle.length() > 500 ? sharedTitle.substring(0, 500) : sharedTitle);
    put(value, "target", "pc");
    put(value, "requestId", requestId);
    submit(
        "POST",
        "/api/links",
        value,
        result -> {
          sending = false;
          link.setText("");
          sharedTitle = "";
          requestId = UUID.randomUUID().toString();
          status.setText("Вкладка открыта в Chrome на ПК");
        });
  }

  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (!visible || destroyed) return;
          refresh();
          main.postDelayed(this, 2000);
        }
      };

  private void refresh() {
    if (api == null || refreshing) return;
    refreshing = true;
    submit(
        "GET",
        "/api/sync",
        null,
        result -> {
          refreshing = false;
          if (status.getText().toString().startsWith("Подключаемся"))
            status.setText("ПК подключён");
          render(result);
        });
  }

  private void render(JSONObject state) {
    JSONObject clip = state.optJSONObject("clipboard");
    if (clip != null) {
      updatingSwitch = true;
      automatic.setChecked(clip.optBoolean("enabled"));
      updatingSwitch = false;
      autoStatus.setText(
          clip.optString("message") + "\nПоследнее копирование · без истории в Синхро");
    }
    JSONArray items = state.optJSONArray("links");
    if (items == null) return;
    String rendered = items.toString();
    if (rendered.equals(renderedInbox)) return;
    renderedInbox = rendered;
    inbox.removeAllViews();
    if (items.length() == 0) {
      inbox.addView(label("Пока нет входящих вкладок", 14, 0xff777b83));
      return;
    }
    for (int index = items.length() - 1; index >= 0; index--) {
      JSONObject item = items.optJSONObject(index);
      if (item == null) continue;
      LinearLayout row = card(inbox);
      TextView title = label(item.optString("title"), 18, Color.WHITE);
      row.addView(title);
      TextView domain = label(Uri.parse(item.optString("url")).getHost(), 12, 0xffa4a7ad);
      domain.setPadding(0, dp(8), 0, dp(10));
      row.addView(domain);
      Runnable open = () -> open(item);
      swipe(title, open);
      swipe(domain, open);
      button("Открыть в Opera  →", row, open);
      button(
          "Убрать",
          row,
          () ->
              submit(
                  "POST",
                  "/api/links/ack",
                  body("id", item.optString("id")),
                  ignored -> refresh()));
    }
  }

  private void open(JSONObject item) {
    try {
      Uri url = Uri.parse(SharedLink.validate(item.optString("url")));
      Intent browser = new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE);
      Intent opera = new Intent(browser).setPackage("com.opera.browser");
      startActivity(opera.resolveActivity(getPackageManager()) != null ? opera : browser);
      if (item.has("id"))
        submit("POST", "/api/links/ack", body("id", item.optString("id")), ignored -> {});
    } catch (Exception error) {
      status.setText("Не удалось открыть ссылку в браузере");
    }
  }

  private interface Result {
    void accept(JSONObject value);
  }

  private void submit(String method, String path, JSONObject body, Result result) {
    if (api == null) {
      status.setText("Сначала подключите ПК по QR на главном экране");
      settingMode = false;
      sending = false;
      refreshing = false;
      return;
    }
    network.execute(
        () -> {
          try {
            JSONObject response = api.request(method, path, body == null ? null : body.toString());
            main.post(
                () -> {
                  if (!destroyed) result.accept(response);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (destroyed) return;
                  settingMode = false;
                  sending = false;
                  refreshing = false;
                  status.setText(
                      "Не удалось связаться с ПК: "
                          + (error instanceof ApiClient.ApiException
                              ? error.getMessage()
                              : "проверьте сеть и сервис Синхро"));
                });
          }
        });
  }

  private void swipe(View view, Runnable action) {
    GestureDetector detector =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              public boolean onDown(MotionEvent event) {
                return true;
              }

              public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (first != null
                    && last.getX() - first.getX() > dp(64)
                    && Math.abs(last.getY() - first.getY()) < dp(60)) {
                  action.run();
                  return true;
                }
                return false;
              }
            });
    view.setOnTouchListener((target, event) -> detector.onTouchEvent(event));
  }

  private static JSONObject body(String key, Object value) {
    JSONObject object = new JSONObject();
    put(object, key, value);
    return object;
  }

  private static void put(JSONObject object, String key, Object value) {
    try {
      object.put(key, value);
    } catch (org.json.JSONException error) {
      throw new IllegalArgumentException(error);
    }
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    return layout;
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout layout = column();
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(0xff1c1e21);
    bg.setCornerRadius(dp(24));
    bg.setStroke(dp(1), 0xff34363b);
    layout.setBackground(bg);
    layout.setPadding(dp(20), dp(20), dp(20), dp(20));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(16);
    parent.addView(layout, lp);
    return layout;
  }

  private TextView label(String value, int size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private EditText input(LinearLayout parent, String hint, boolean multi) {
    TextInputLayout wrapper = new TextInputLayout(this);
    wrapper.setHint(hint);
    wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(16);
    parent.addView(wrapper, lp);
    com.google.android.material.textfield.TextInputEditText edit =
        new com.google.android.material.textfield.TextInputEditText(this);
    edit.setTextColor(0xfff6f5ef);
    edit.setSingleLine(!multi);
    edit.setMinLines(multi ? 3 : 1);
    edit.setMaxLines(multi ? 7 : 1);
    edit.setInputType(
        multi
            ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            : android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    edit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(multi ? 16000 : 8192)});
    wrapper.addView(edit);
    return edit;
  }

  private MaterialButton button(String label, LinearLayout parent, Runnable action) {
    MaterialButton button = new MaterialButton(this);
    button.setText(label);
    button.setCornerRadius(dp(16));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(56));
    lp.topMargin = dp(10);
    parent.addView(button, lp);
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
