package com.kenan.optishare.transfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.kenan.optishare.OptiShareApp;
import com.kenan.optishare.R;
import com.kenan.optishare.V2Activity;

/** Approval gate that works even when the Activity is backgrounded. */
final class IncomingApproval {
    private static final Object LOCK = new Object();
    private static final String CHANNEL = "optishare_incoming";
    private static final int NOTIFICATION_ID = 2210;
    private static String pendingSession;
    private static Boolean decision;

    private IncomingApproval() {}

    static void begin(String sessionId) {
        synchronized (LOCK) {
            pendingSession = sessionId;
            decision = null;
        }
        showNotification();
    }

    static boolean await(String sessionId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (LOCK) {
            while (sessionId != null && sessionId.equals(pendingSession) && decision == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                LOCK.wait(remaining);
            }
            boolean accepted = sessionId != null && sessionId.equals(pendingSession) && Boolean.TRUE.equals(decision);
            if (sessionId != null && sessionId.equals(pendingSession)) {
                pendingSession = null;
                decision = null;
            }
            cancelNotification();
            return accepted;
        }
    }

    static void decide(boolean accepted) {
        synchronized (LOCK) {
            if (pendingSession == null) return;
            decision = accepted;
            LOCK.notifyAll();
        }
        cancelNotification();
    }

    static void cancel() {
        synchronized (LOCK) {
            pendingSession = null;
            decision = Boolean.FALSE;
            LOCK.notifyAll();
        }
        cancelNotification();
    }

    private static void showNotification() {
        Context context;
        try { context = OptiShareApp.context(); }
        catch (Exception ignored) { return; }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Incoming transfers", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Approval requests for incoming OptiShare transfers");
            manager.createNotificationChannel(channel);
        }
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(
                context, 10, new Intent(context, V2Activity.class), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent accept = PendingIntent.getService(
                context, 11, new Intent(context, TransferService.class).setAction(TransferService.ACTION_ACCEPT), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent decline = PendingIntent.getService(
                context, 12, new Intent(context, TransferService.class).setAction(TransferService.ACTION_DECLINE), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle("Incoming OptiShare transfer")
                .setContentText("Accept or decline the secured incoming batch")
                .setContentIntent(open)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, "Decline", decline)
                .addAction(0, "Accept", accept);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static void cancelNotification() {
        try {
            Context context = OptiShareApp.context();
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) { }
    }
}
