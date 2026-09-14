package io.tsuyu.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

import io.tsuyu.app.core.Keys;
import io.tsuyu.app.core.ProfileCipher;

public class TsuyuApp extends Application {
    public static final String APP_ID = "1:471541334599:android:tsuyu0001";
    private static TsuyuApp inst;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setApiKey("AIzaSyBm0mIvHVznIeF2PoFk6dtdaiT5r877wyA")
                .setApplicationId(APP_ID)
                .setProjectId("meow-874ce")
                .setStorageBucket("meow-874ce.appspot.com")
                .setDatabaseUrl("https://meow-874ce-default-rtdb.europe-west1.firebasedatabase.app")
                .setGoogleAnalyticsEnabled(false)
                .build();
        if (!FirebaseApp.getApps(this).contains(FirebaseApp.DEFAULT_APP_NAME)) {
            FirebaseApp.initializeApp(this, options);
        }
        FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        createChannels();
        prefs = getSharedPreferences("tsuyu", MODE_PRIVATE);
    }

    public static TsuyuApp get() { return inst; }
    public SharedPreferences prefs() { return prefs; }
    public String myUid() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser() != null
                    ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        } catch (Throwable t) { return null; }
    }
    public boolean isAuthed() { return myUid() != null; }

    public void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel msg = new NotificationChannel("tsuyu_messages", "Сообщения", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("Новые сообщения");
        msg.enableVibration(true);
        NotificationChannel call = new NotificationChannel("tsuyu_calls", "Звонки", NotificationManager.IMPORTANCE_MAX);
        call.setDescription("Входящие звонки");
        call.enableVibration(true);
        NotificationChannel svc = new NotificationChannel("tsuyu_service", "Фоновая служба", NotificationManager.IMPORTANCE_MIN);
        svc.setDescription("Tsuyu работает в фоне");
        svc.setShowBadge(false);
        nm.createNotificationChannel(msg);
        nm.createNotificationChannel(call);
        nm.createNotificationChannel(svc);
    }

    public void ensureMyKeys() {
        try { Keys.ensure(this); } catch (Throwable ignored) {}
    }
}
