package app.dsh.remote;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 沉浸式：内容一直画到系统栏底下（手势小白条浮在内容上，不是一条白带）。
 *
 * <p>顶部栏自己吃掉状态栏高度，所以它看上去仍是普通顶栏；根布局底部吃"输入法与系统栏里更高的那个"，
 * 于是键盘弹起时输入框贴着键盘、键盘收起时最后一屏内容不会被手势条盖住。
 */
public final class Ui {

  private Ui() {}

  public static void edgeToEdge(final Activity a, final View root, final View topBar) {
    final Window w = a.getWindow();
    if (Build.VERSION.SDK_INT >= 30) {
      w.setDecorFitsSystemWindows(false);
    } else {
      w.getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    w.setStatusBarColor(Color.TRANSPARENT);
    w.setNavigationBarColor(Color.TRANSPARENT);
    if (Build.VERSION.SDK_INT >= 29) {
      w.setNavigationBarContrastEnforced(false);
    }

    boolean night =
        (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(w, w.getDecorView());
    c.setAppearanceLightStatusBars(!night);
    c.setAppearanceLightNavigationBars(!night);

    final int barTop = topBar == null ? 0 : topBar.getPaddingTop();
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        new OnApplyWindowInsetsListener() {
          @Override
          public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            if (topBar != null) {
              topBar.setPadding(
                  topBar.getPaddingLeft(),
                  barTop + bars.top,
                  topBar.getPaddingRight(),
                  topBar.getPaddingBottom());
            }
            v.setPadding(
                v.getPaddingLeft(),
                v.getPaddingTop(),
                v.getPaddingRight(),
                Math.max(ime.bottom, bars.bottom));
            // 真机上「小白条/键盘压住内容」这类问题只能量出来，所以把每次 insets 报给 logcat：
            //   adb logcat -s dsh-remote:I
            Log.i(
                "dsh-remote",
                "insets bars=" + bars.top + "," + bars.bottom + " ime=" + ime.bottom + " night=" + night);
            return insets;
          }
        });
    ViewCompat.requestApplyInsets(root);
  }
}