package com.tsuyu.messenger.util;

import android.content.Context;

import com.tsuyu.messenger.data.Prefs;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class Fmt {

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", new Locale("ru"));
    private static final SimpleDateFormat DAY = new SimpleDateFormat("d MMMM", new Locale("ru"));
    private static final SimpleDateFormat DAY_YEAR = new SimpleDateFormat("d MMMM yyyy", new Locale("ru"));

    private Fmt() {}

    public static String time(long ts) { return TIME.format(new Date(ts)); }

    public static String daySeparator(long ts) {
        Calendar c = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        c.setTimeInMillis(ts);
        if (sameDay(c, t)) return "сегодня";
        t.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(c, t)) return "вчера";
        Calendar now = Calendar.getInstance();
        return c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                ? DAY.format(new Date(ts)) : DAY_YEAR.format(new Date(ts));
    }

    public static boolean sameDayTs(long a, long b) {
        Calendar x = Calendar.getInstance(); x.setTimeInMillis(a);
        Calendar y = Calendar.getInstance(); y.setTimeInMillis(b);
        return sameDay(x, y);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** Telegram-style last-seen line, honouring custom wording. */
    public static String lastSeen(Context ctx, boolean online, long lastSeen, boolean hidden) {
        Prefs p = new Prefs(ctx);
        if (online) return p.wordOnline();
        if (hidden || lastSeen <= 0) return p.wordLastSeen() + " недавно";
        long diff = System.currentTimeMillis() - lastSeen;
        if (diff < 60_000L) return p.wordLastSeen() + " только что";
        if (diff < 3_600_000L) return p.wordLastSeen() + " " + (diff / 60_000L) + " мин назад";
        if (sameDayTs(lastSeen, System.currentTimeMillis()))
            return p.wordLastSeen() + " в " + time(lastSeen);
        return p.wordLastSeen() + " " + daySeparator(lastSeen) + " в " + time(lastSeen);
    }

    public static String duration(long ms) {
        long total = ms / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    public static String fileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }
}
