package app.dsh.remote;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * The page calls {@code AndroidBridge.notify(title, body)} from notify.js (injected by
 * dsh-lan-bridge.cjs) whenever DSH forwards an event worth waking the phone for: a session
 * finished, an approval is waiting, a question is waiting. JavascriptInterface methods run on a
 * binder thread, so everything here hops to the main thread before touching the
 * NotificationManager.
 *
 * <p>Tap action is just "open the app": the WebView has no URL routing to deep-link into a
 * session, and the DSH page restores whatever the user was last looking at.
 */
final class AndroidBridge {
  private static final String TAG = "dsh-remote";
  private static final String CHANNEL = "dsh";
  private static final int REQUEST_NOTIFICATIONS = 43;

  private final Activity activity;

  AndroidBridge(Activity activity) {
    this.activity = activity;
  }

  /** Called from onCreate once the WebView exists. */
  static void attach(Activity activity, android.webkit.WebView web) {
    web.addJavascriptInterface(new AndroidBridge(activity), "AndroidBridge");
    if (Build.VERSION.SDK_INT >= 33
        && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(
          new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }
  }

  @JavascriptInterface
  public void notify(final String title, final String body) {
    activity.runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            show(title, body);
          }
        });
  }

  private void show(String title, String body) {
    NotificationManager nm =
        (NotificationManager) activity.getSystemService(Activity.NOTIFICATION_SERVICE);
    if (nm == null) return;
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel channel =
          new NotificationChannel(
              CHANNEL, activity.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH);
      channel.setDescription("DSH task, approval and question events");
      nm.createNotificationChannel(channel);
    }
    PendingIntent contentIntent =
        PendingIntent.getActivity(
            activity,
            0,
            new Intent(activity, WebActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(activity, CHANNEL)
            : new Notification.Builder(activity);
    builder
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title == null ? "" : title)
        .setContentText(body == null ? "" : body)
        .setContentIntent(contentIntent)
        .setAutoCancel(true);
    if (Build.VERSION.SDK_INT >= 16) builder = builder.setPriority(Notification.PRIORITY_HIGH);
    try {
      nm.notify(1, builder.build());
    } catch (SecurityException e) {
      // POST_NOTIFICATIONS denied: the phone shows nothing, the page keeps working.
      Log.w(TAG, "notification dropped: " + e);
    }
  }
}
