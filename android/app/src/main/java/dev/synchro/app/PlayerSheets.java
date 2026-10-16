package dev.synchro.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Small native bottom sheets keep secondary controls out of the video viewport. */
final class PlayerSheets {
  private final Context context;
  private final PlaybackController playback;
  private final Runnable onDismiss;
  private BottomSheetDialog currentDialog;

  PlayerSheets(Context context, PlaybackController playback, Runnable onDismiss) {
    this.context = context;
    this.playback = playback;
    this.onDismiss = onDismiss;
  }

  private final class Sheet {
    final BottomSheetDialog dialog = new BottomSheetDialog(context);
    final LinearLayout content = new LinearLayout(context);

    Sheet(String title, String description) {
      content.setOrientation(LinearLayout.VERTICAL);
      content.setPadding(dp(24), dp(12), dp(24), dp(28));
      GradientDrawable background = new GradientDrawable();
      background.setColor(0xE61C1E21);
      background.setCornerRadii(new float[] {dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
      content.setBackground(background);
      View handle = new View(context);
      GradientDrawable handleShape = new GradientDrawable();
      handleShape.setColor(0x557F838B);
      handleShape.setCornerRadius(dp(2));
      handle.setBackground(handleShape);
      LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
      hp.gravity = Gravity.CENTER_HORIZONTAL;
      hp.bottomMargin = dp(22);
      content.addView(handle, hp);
      TextView heading = text(title, 23, Color.WHITE);
      heading.setTypeface(null, Typeface.BOLD);
      content.addView(heading);
      if (!description.isEmpty()) {
        TextView caption = text(description, 13, 0xFFACAFB6);
        caption.setPadding(0, dp(8), 0, dp(12));
        content.addView(caption);
      }
      ScrollView scroll = new ScrollView(context);
      scroll.addView(content);
      dialog.setContentView(scroll, new android.view.ViewGroup.LayoutParams(-1, -2));
      dialog.setOnDismissListener(ignored -> onDismiss.run());
    }

    void option(String label, String description, boolean selected, Runnable action) {
      LinearLayout row = new LinearLayout(context);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(16), dp(12), dp(12), dp(12));
      row.setMinimumHeight(dp(60));
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(selected ? 0x22FFD34E : 0x00282B30);
      bg.setCornerRadius(dp(16));
      row.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(0x22FFFFFF), bg, null));
      LinearLayout labels = new LinearLayout(context);
      labels.setOrientation(LinearLayout.VERTICAL);
      TextView name = text(label, 16, selected ? 0xFFFFD34E : 0xFFF6F5EF);
      labels.addView(name);
      if (!description.isEmpty()) {
        TextView hint = text(description, 12, 0xFFA4A7AD);
        hint.setPadding(0, dp(4), 0, 0);
        labels.addView(hint);
      }
      row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      if (selected) {
        TextView mark = text("✓", 20, 0xFFFFD34E);
        mark.setPadding(dp(12), 0, 0, 0);
        row.addView(mark);
      }
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.topMargin = dp(4);
      content.addView(row, lp);
      row.setOnClickListener(
          view -> {
            dialog.dismiss();
            action.run();
          });
    }

    void show() {
      currentDialog = dialog;
      dialog.show();
      dialog
          .getBehavior()
          .setMaxHeight((int) (context.getResources().getDisplayMetrics().heightPixels * .9f));
      dialog.getBehavior().setSkipCollapsed(true);
      dialog
          .getBehavior()
          .setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
      View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
      if (frame != null) frame.setBackgroundColor(Color.TRANSPARENT);
      if (dialog.getWindow() != null) {
        dialog.getWindow().setDimAmount(.35f);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(dialog.getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat bars =
            androidx.core.view.WindowCompat.getInsetsController(
                dialog.getWindow(), dialog.getWindow().getDecorView());
        bars.setSystemBarsBehavior(
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
      }
    }
  }

  void close() {
    if (currentDialog != null) {
      currentDialog.setOnDismissListener(null);
      currentDialog.dismiss();
      currentDialog = null;
    }
  }

  void queue() {
    if (!playback.hasSession()) return;
    SessionState session = playback.session();
    Sheet sheet =
        new Sheet(
            "Плейлист",
            session.items.size()
                + " файлов с компьютера"
                + (session.playlistWarning.isEmpty() ? "" : "\n" + session.playlistWarning));
    for (int index = 0; index < session.items.size(); index++) {
      int selected = index;
      sheet.option(
          (index + 1) + ". " + session.items.get(index).title,
          "",
          index == playback.index(),
          () -> playback.selectItem(selected));
    }
    sheet.show();
  }

  void tracks(boolean subtitles) {
    if (!playback.hasSession()) return;
    List<PlaybackController.Track> tracks =
        subtitles ? playback.subtitleTracks() : playback.audioTracks();
    Sheet sheet =
        new Sheet(
            subtitles ? "Субтитры" : "Озвучка",
            subtitles ? "Дорожка и синхронизация" : "Аудиодорожки текущего файла");
    if (subtitles)
      sheet.option(
          "Выключены", "", !playback.subtitleEnabled(), () -> playback.selectSubtitle(null));
    for (PlaybackController.Track track : tracks) {
      boolean selected =
          track.ordinal == (subtitles ? playback.subtitleIndex() : playback.audioIndex())
              && (!subtitles || playback.subtitleEnabled());
      sheet.option(
          track.name,
          "Дорожка " + (track.ordinal + 1),
          selected,
          () -> {
            if (subtitles) playback.selectSubtitle(track);
            else playback.selectAudio(track);
          });
    }
    if (subtitles)
      sheet.option(
          "Задержка субтитров", formatDelay(playback.subtitleDelayMs()), false, this::delay);
    if (tracks.isEmpty()) sheet.content.addView(text("Дорожки пока недоступны", 14, 0xFFA4A7AD));
    sheet.show();
  }

  void rate() {
    if (!playback.hasSession()) return;
    Sheet sheet = new Sheet("Скорость", "Изменяется на телефоне");
    float[] rates = {.75f, 1f, 1.25f, 1.5f, 2f};
    for (float rate : rates)
      sheet.option(
          Float.toString(rate).replaceAll("\\.0$", "") + "×",
          "",
          Math.abs(rate - playback.rate()) < .01,
          () -> playback.setRate(rate));
    sheet.show();
  }

  void settings() {
    if (!playback.hasSession()) return;
    Sheet sheet =
        new Sheet("Настройки просмотра", "Озвучка и субтитры передаются вместе с позицией");
    if (playback.session().supportsSubtitles)
      sheet.option(
          "Задержка субтитров", formatDelay(playback.subtitleDelayMs()), false, this::delay);
    if (playback.session().supportsRate)
      sheet.option("Скорость", playback.rate() + "×", false, this::rate);
    sheet.option(
        "Звук",
        playback.audioOutput() == PlaybackController.AudioOutput.STANDARD
            ? "Стандартный вывод"
            : "Совместимый вывод",
        false,
        this::audioOutput);
    sheet.option("О приложении", "Synchro · " + AppDesign.version(context), false, this::about);
    sheet.show();
  }

  void audioOutput() {
    Sheet sheet =
        new Sheet(
            "Вывод звука",
            "Если слышны эхо, треск или искажения, сравните два режима на этом телефоне."
                + " Звук передаётся в исходном качестве.");
    sheet.option(
        "Стандартный",
        "Android AudioTrack",
        playback.audioOutput() == PlaybackController.AudioOutput.STANDARD,
        () -> playback.setAudioOutput(PlaybackController.AudioOutput.STANDARD));
    sheet.option(
        "Совместимый",
        "OpenSL ES",
        playback.audioOutput() == PlaybackController.AudioOutput.COMPATIBLE,
        () -> playback.setAudioOutput(PlaybackController.AudioOutput.COMPATIBLE));
    sheet.show();
  }

  void delay() {
    Sheet sheet = new Sheet("Synchroнизация субтитров", "Плюс — позже, минус — раньше");
    TextView value = text(formatDelay(playback.subtitleDelayMs()), 32, 0xFFFFD34E);
    value.setGravity(Gravity.CENTER);
    value.setPadding(0, dp(16), 0, dp(16));
    sheet.content.addView(value);
    LinearLayout row = new LinearLayout(context);
    MaterialButton minus =
        new MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    MaterialButton plus =
        new MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    minus.setText("−100 мс");
    plus.setText("+100 мс");
    row.addView(minus, new LinearLayout.LayoutParams(0, dp(52), 1));
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(52), 1);
    pp.leftMargin = dp(12);
    row.addView(plus, pp);
    sheet.content.addView(row);
    minus.setOnClickListener(
        v -> {
          playback.setSubtitleDelay(playback.subtitleDelayMs() - 100);
          value.setText(formatDelay(playback.subtitleDelayMs()));
        });
    plus.setOnClickListener(
        v -> {
          playback.setSubtitleDelay(playback.subtitleDelayMs() + 100);
          value.setText(formatDelay(playback.subtitleDelayMs()));
        });
    EditText input = new EditText(context);
    input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
    input.setSingleLine(true);
    input.setHint("Точное значение, мс");
    input.setTextColor(Color.WHITE);
    sheet.content.addView(input);
    MaterialButton apply = new MaterialButton(context);
    apply.setText("Применить значение");
    sheet.content.addView(apply);
    apply.setOnClickListener(
        v -> {
          try {
            long ms = Long.parseLong(input.getText().toString());
            if (ms < -3_600_000 || ms > 3_600_000) throw new NumberFormatException();
            playback.setSubtitleDelay(ms);
            sheet.dialog.dismiss();
          } catch (NumberFormatException error) {
            input.setError("Введите значение от −3600000 до 3600000 мс");
          }
        });
    sheet.option("Сбросить", "0 мс", false, () -> playback.setSubtitleDelay(0));
    sheet.show();
  }

  void about() {
    Sheet sheet = new Sheet("Synchro", "Продолжайте просмотр с компьютера по локальной сети");
    sheet.content.addView(
        text(
            "Версия "
                + AppDesign.version(context)
                + "\n"
                + "Собственный код — MIT\n"
                + "LibVLC 3.7.7 — LGPL 2.1+\n"
                + "Material Components и ZXing — Apache 2.0",
            14,
            0xFFA4A7AD));
    sheet.option(
        "Лицензии компонентов",
        "Тексты лицензий и ссылки на исходники",
        false,
        () -> {
          Sheet licenses = new Sheet("Открытые компоненты", "");
          licenses.content.addView(
              text(
                  asset("THIRD_PARTY_NOTICES.txt")
                      + "\n\n"
                      + asset("LGPL-2.1.txt")
                      + "\n\n"
                      + asset("APACHE-2.0.txt"),
                  12,
                  0xFFACAFB6));
          licenses.show();
        });
    sheet.show();
  }

  private String asset(String name) {
    try (InputStream input = context.getAssets().open(name);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[4096];
      int count;
      while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
      return output.toString("UTF-8");
    } catch (Exception error) {
      return "Текст лицензии: " + name;
    }
  }

  private TextView text(String text, int size, int color) {
    TextView view = new TextView(context);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setLineSpacing(dp(3), 1);
    return view;
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private static String formatDelay(long value) {
    return (value > 0 ? "+" : "") + value + " мс";
  }
}
