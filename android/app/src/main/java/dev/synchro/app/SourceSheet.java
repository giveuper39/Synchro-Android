package dev.synchro.app;

import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** A source has an honest ready/setup state supplied by the desktop. */
final class SourceSheet {
  static BottomSheetDialog show(
      AppCompatActivity a,
      JSONObject catalog,
      Consumer<String> select,
      Runnable setup,
      Runnable refresh) {
    BottomSheetDialog dialog = new BottomSheetDialog(a);
    LinearLayout root = AppDesign.column(a);
    root.setPadding(
        AppDesign.dp(a, 20), AppDesign.dp(a, 18), AppDesign.dp(a, 20), AppDesign.dp(a, 24));
    root.setBackground(AppDesign.background(a, AppDesign.BACKGROUND, 28));
    TextView title = AppDesign.text(a, "Откуда продолжить?", 24, AppDesign.TEXT);
    title.setTypeface(title.getTypeface(), Typeface.BOLD);
    root.addView(title);
    TextView help =
        AppDesign.text(a, "Плееры и открытые страницы на вашем ПК", 12, AppDesign.MUTED);
    help.setPadding(0, AppDesign.dp(a, 8), 0, AppDesign.dp(a, 18));
    root.addView(help);
    ScrollView scroll = new ScrollView(a);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, AppDesign.dp(a, 430)));
    LinearLayout list = AppDesign.column(a);
    scroll.addView(list);
    JSONArray players = catalog.optJSONArray("players");
    String selected = catalog.optString("selected");
    if (players == null || players.length() == 0)
      list.addView(AppDesign.text(a, "Ищем источники на компьютере…", 14, AppDesign.MUTED));
    if (players != null)
      for (int i = 0; i < players.length(); i++) {
        JSONObject source = players.optJSONObject(i);
        if (source == null) continue;
        String id = source.optString("id");
        boolean active = selected.equals(id), ready = source.optBoolean("ready");
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(
            AppDesign.dp(a, 16), AppDesign.dp(a, 14), AppDesign.dp(a, 16), AppDesign.dp(a, 14));
        row.setBackground(AppDesign.background(a, active ? 0xff303025 : AppDesign.SURFACE, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = AppDesign.dp(a, 8);
        list.addView(row, lp);
        TextView symbol =
            AppDesign.text(
                a,
                source.optString("kind").equals("youtube") ? "▶" : "▣",
                20,
                active ? AppDesign.AMBER : AppDesign.MUTED);
        row.addView(symbol, new LinearLayout.LayoutParams(AppDesign.dp(a, 36), -2));
        LinearLayout labels = AppDesign.column(a);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = AppDesign.text(a, source.optString("name"), 14, AppDesign.TEXT);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        labels.addView(name);
        TextView detail = AppDesign.text(a, source.optString("detail"), 11, AppDesign.MUTED);
        detail.setPadding(0, AppDesign.dp(a, 5), 0, 0);
        detail.setMaxLines(2);
        labels.addView(detail);
        row.addView(
            AppDesign.text(
                a,
                active ? "✓" : ready ? "●" : "○",
                16,
                active ? AppDesign.AMBER : ready ? 0xff8bd5af : AppDesign.MUTED));
        row.setOnClickListener(
            v -> {
              dialog.dismiss();
              select.accept(id);
            });
      }
    MaterialButton setupButton = AppDesign.button(a, "Настроить источники на ПК", false);
    root.addView(setupButton, new LinearLayout.LayoutParams(-1, AppDesign.dp(a, 52)));
    setupButton.setOnClickListener(
        v -> {
          dialog.dismiss();
          setup.run();
        });
    TextView reload = AppDesign.text(a, "Обновить список", 12, AppDesign.MUTED);
    reload.setGravity(Gravity.CENTER);
    reload.setPadding(0, AppDesign.dp(a, 14), 0, AppDesign.dp(a, 4));
    root.addView(reload);
    reload.setOnClickListener(
        v -> {
          dialog.dismiss();
          refresh.run();
        });
    dialog.setContentView(root);
    dialog.show();
    return dialog;
  }
}
