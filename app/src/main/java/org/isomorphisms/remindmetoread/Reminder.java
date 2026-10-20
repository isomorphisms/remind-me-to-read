package org.isomorphisms.remindmetoread;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Calendar;
import java.util.List;

/** Opt-in, inexact local reminders; no exact-alarm special access is needed. */
public final class Reminder {
    private static final String PREFS = "reading-settings";
    private static final String CHANNEL = "reading";
    private Reminder() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void enable(Context context, int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new IllegalArgumentException();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", true).putInt("hour", hour).putInt("minute", minute).apply();
        schedule(context);
    }

    static void disable(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false).apply();
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(intent(context));
    }

    static void schedule(Context context) {
        if (!enabled(context)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, prefs.getInt("hour", 20));
        next.set(Calendar.MINUTE, prefs.getInt("minute", 0));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_MONTH, 1);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), intent(context));
    }

    private static PendingIntent intent(Context context) {
        return PendingIntent.getBroadcast(context, 30, new Intent(context, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void channel(Context context) {
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Reading reminders",
                    NotificationManager.IMPORTANCE_DEFAULT);
            notifications.createNotificationChannel(channel);
        }
    }

    static void notifyIfNeeded(Context context) {
        if (!enabled(context)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        try (BookStore store = new BookStore(context)) {
            List<BookStore.Book> books = store.all();
            if (books.isEmpty()) return;
            BookStore.Book book = null;
            for (BookStore.Book candidate : books) {
                if (candidate.isPaper() && candidate.currentPage >= candidate.totalPages) continue;
                if (candidate.isText() && candidate.contentLength > 0 &&
                        candidate.charOffset >= candidate.contentLength) continue;
                book = candidate;
                if (candidate.lastReadAt > 0) break;
            }
            if (book == null) return;
            Calendar midnight = Calendar.getInstance();
            midnight.set(Calendar.HOUR_OF_DAY, 0);
            midnight.set(Calendar.MINUTE, 0);
            midnight.set(Calendar.SECOND, 0);
            midnight.set(Calendar.MILLISECOND, 0);
            if (book.lastReadAt >= midnight.getTimeInMillis()) return;

            Intent open = new Intent(context, ReadActivity.class);
            open.putExtra("book_id", book.id);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent tap = PendingIntent.getActivity(context, 31, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            channel(context);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("Time to read?")
                    .setContentText("Continue " + book.title)
                    .setContentIntent(tap).setAutoCancel(true).build();
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(1, notification);
        }
    }
}

final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // Schedule the next alarm before doing database work.
        Reminder.schedule(context);
        Reminder.notifyIfNeeded(context);
    }
}

final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) Reminder.schedule(context);
    }
}
