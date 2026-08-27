package com.kenan.optishare.transfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.kenan.optishare.ApprovalActivity;
import com.kenan.optishare.OptiShareApp;
import com.kenan.optishare.R;

/**
 * Process-local approval gate backed by a high-priority notification and a focused approval screen.
 * It is used for both peer security-code verification and incoming batch consent.
 */
final class IncomingApproval {
    private static final Object LOCK = new Object();
    private static final String CHANNEL = "optishare_approvals";
    private static final int NOTIFICATION_ID = 2210;

    private static String pendingKey;
    private static Boolean decision;
    private static String pendingTitle;
    private static String pendingText;

    private IncomingApproval() {}

    static void begin(String key) {
        begin(key, "Incoming OptiShare transfer",
                "Accept or decline the secured incoming batch");
    }

    static void begin(String key, String title, String text) {
        synchronized (LOCK) {
            pendingKey = key;
            decision = null;
            pendingTitle = title;
            pendingText = text;
        }
        showApprovalSurface();
    }

    static boolean await(String key, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (LOCK) {
            while (key != null && key.equals(pendingKey) && decision == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                LOCK.wait(remaining);
            }
            boolean accepted = key != null && key.equals(pendingKey)
                    && Boolean.TRUE.equals(decision);
            if (key != null && key.equals(pendingKey)) clearLocked();
            cancelNotification();
            return accepted;
        }
    }

    static void decide(boolean accepted) {
        synchronized (LOCK) {
            if (pendingKey == null) return;
            decision = accepted;
            LOCK.notifyAll();
        }
        cancelNotification();
    }

    static void cancel() {
        synchronized (LOCK) {
            if (pendingKey != null) {
                decision = Boolean.FALSE;
                LOCK.notifyAll();
            }
            clearLocked();
        }
        cancelNotification();
    }

    static boolean hasPending() {
        synchronized (LOCK) {
            return pendingKey != null && decision == null;
        }
    }

    private static void clearLocked() {
        pendingKey = null;
        decision = null;
        pendingTitle = null;
        pendingText = null;
    }

    private static void showApprovalSurface() {
        Context context;
        try {
            context = OptiShareApp.context();
        } catch (Exception ignored) {
            return;
        }

        String title;
        String text;
        synchronized (LOCK) {
            title = pendingTitle == null ? "OptiShare approval required" : pendingTitle;
            text = pendingText == null ? "Open OptiShare to continue" : pendingText;
        }

        showNotification(context, title, text);
        if (OptiShareApp.isForeground()) {
            try {
                Intent approvalScreen = new Intent(context, ApprovalActivity.class)
                        .putExtra(ApprovalActivity.EXTRA_TITLE, title)
                        .putExtra(ApprovalActivity.EXTRA_TEXT, text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(approvalScreen);
            } catch (Exception ignored) {
                // Notification remains available when an OEM blocks foreground activity promotion.
            }
        }
    }

    private static void showNotification(Context context, String title, String text) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Transfer approvals", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Security verification and incoming transfer approvals");
            manager.createNotificationChannel(channel);
        }

        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent approvalScreen = new Intent(context, ApprovalActivity.class)
                .putExtra(ApprovalActivity.EXTRA_TITLE, title)
                .putExtra(ApprovalActivity.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(
                context,
                10,
                approvalScreen,
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);

        PendingIntent accept = PendingIntent.getService(
                context,
                11,
                new Intent(context, TransferService.class)
                        .setAction(TransferService.ACTION_ACCEPT),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        PendingIntent decline = PendingIntent.getService(
                context,
                12,
                new Intent(context, TransferService.class)
                        .setAction(TransferService.ACTION_DECLINE),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_optishare)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, "Decline", decline)
                .addAction(0, "Confirm", accept);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static void cancelNotification() {
        try {
            Context context = OptiShareApp.context();
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }
}
