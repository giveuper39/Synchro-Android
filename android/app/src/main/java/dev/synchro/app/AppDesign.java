package dev.synchro.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import java.util.function.Consumer;

/** Shared visual language: calm surfaces, one primary action and fixed navigation. */
final class AppDesign {
  static final int BACKGROUND = 0xff101216,
      SURFACE = 0xff191d24,
      TEXT = 0xfff9f6ef,
      MUTED = 0xff9ca4b3,
      AMBER = 0xfff4c75b;

  static int dp(Context c, int value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable background(Context c, int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static TextView text(Context c, String value, int size, int color) {
    TextView v = new TextView(c);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setTypeface(ResourcesCompat.getFont(c, R.font.manrope));
    return v;
  }

  static LinearLayout column(Context c) {
    LinearLayout v = new LinearLayout(c);
    v.setOrientation(LinearLayout.VERTICAL);
    v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    return v;
  }

  static MaterialButton button(Context c, String title, boolean primary) {
    MaterialButton b = new MaterialButton(c);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(13);
    b.setCornerRadius(dp(c, 16));
    b.setBackgroundTintList(ColorStateList.valueOf(primary ? AMBER : 0xff262b35));
    b.setTextColor(primary ? BACKGROUND : TEXT);
    b.setMinimumHeight(dp(c, 52));
    return b;
  }

  static String version(Context context) {
    try {
      return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
    } catch (Exception ignored) {
      return "";
    }
  }

  static final class Shell {
    final AppCompatActivity activity;
    final LinearLayout content;
    final TextView heading, caption;
    final LinearLayout[] items = new LinearLayout[4];

    Shell(AppCompatActivity a, String title, String selected, Consumer<String> action) {
      activity = a;
      WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
      LinearLayout outer = column(a);
      outer.setBackgroundColor(BACKGROUND);
      outer.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
      ScrollView scroll = new ScrollView(a);
      scroll.setFillViewport(true);
      outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
      content = column(a);
      content.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 24));
      scroll.addView(content);
      LinearLayout brand = new LinearLayout(a);
      brand.setGravity(Gravity.CENTER_VERTICAL);
      content.addView(brand, new LinearLayout.LayoutParams(-1, dp(a, 52)));
      ImageView mark = new ImageView(a);
      mark.setImageResource(R.drawable.ic_brand);
      brand.addView(mark, new LinearLayout.LayoutParams(dp(a, 32), dp(a, 32)));
      TextView word = text(a, "Synchro", 23, TEXT);
      word.setTypeface(word.getTypeface(), Typeface.BOLD);
      LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
      wp.leftMargin = dp(a, 10);
      brand.addView(word, wp);
      heading = text(a, title, 28, TEXT);
      heading.setTypeface(heading.getTypeface(), Typeface.BOLD);
      heading.setLetterSpacing(-.035f);
      heading.setPadding(0, dp(a, 22), 0, 0);
      content.addView(heading);
      caption = text(a, "", 13, MUTED);
      caption.setPadding(0, dp(a, 6), 0, dp(a, 12));
      content.addView(caption);
      LinearLayout nav = new LinearLayout(a);
      nav.setBackgroundColor(SURFACE);
      outer.addView(nav, new LinearLayout.LayoutParams(-1, dp(a, 72)));
      String[] names = {"Просмотр", "Буфер", "Вкладки", "Устройства"},
          keys = {"watch", "clipboard", "tabs", "devices"};
      int[] icons = {
        R.drawable.ic_watch, R.drawable.ic_clipboard, R.drawable.ic_tabs, R.drawable.ic_settings
      };
      for (int i = 0; i < 4; i++) {
        LinearLayout item = column(a);
        items[i] = item;
        item.setGravity(Gravity.CENTER);
        item.setContentDescription(names[i]);
        nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        ImageView icon = new ImageView(a);
        icon.setImageResource(icons[i]);
        item.addView(icon, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
        TextView label = text(a, names[i], 10, MUTED);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(a, 5), 0, 0);
        item.addView(label);
        String key = keys[i];
        item.setOnClickListener(v -> action.accept(key));
      }
      select(selected);
      a.setContentView(outer);
      ViewCompat.setOnApplyWindowInsetsListener(
          outer,
          (v, insets) -> {
            androidx.core.graphics.Insets b =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
          });
    }

    void select(String key) {
      String[] keys = {"watch", "clipboard", "tabs", "devices"};
      for (int i = 0; i < 4; i++) {
        int c = key.equals(keys[i]) ? AMBER : MUTED;
        ((ImageView) items[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(c));
        ((TextView) items[i].getChildAt(1)).setTextColor(c);
      }
    }

    void navigate(String key) {
      activity.startActivity(
          new Intent(activity, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
              .putExtra("screen", key));
      activity.finish();
    }
  }
}
