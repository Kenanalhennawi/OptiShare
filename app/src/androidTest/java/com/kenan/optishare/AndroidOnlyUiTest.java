package com.kenan.optishare;

import android.Manifest;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.rule.GrantPermissionRule;

import com.kenan.optishare.ui.UiText;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AndroidOnlyUiTest {
    private final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS);
    private final ActivityScenarioRule<V2Activity> activity =
            new ActivityScenarioRule<>(V2Activity.class);

    @Rule public final RuleChain rules = RuleChain.outerRule(permissions).around(activity);

    @Test public void homeAndReceiveExposeOnlyAndroidExperience() {
        activity.getScenario().onActivity(screen -> {
            View root = screen.getWindow().getDecorView();
            assertTrue(hasText(root, UiText.get(screen, "Fast. Private. Resumable.")));
            assertFalse(hasText(root, "Windows Companion"));
            assertFalse(hasText(root, "browser / PC"));

            View settings = findText(root, screen.getString(R.string.settings));
            assertNotNull(settings);
            View receive = findText(root, UiText.get(screen, "RECEIVE"));
            assertNotNull(receive);
            receive.performClick();

            root = screen.getWindow().getDecorView();
            assertTrue(hasExactText(root, UiText.get(screen, "Receive")));
            assertTrue(hasText(root, UiText.get(screen, "Keep this screen open while the sender connects. Android-to-Android transfers use authenticated ECDH and AES-GCM encryption.")));
            assertFalse(hasText(root, "Browser mode"));
            assertFalse(hasText(root, "Windows"));
        });
    }

    @Test public void settingsExposePrivateAndroidControls() {
        try (ActivityScenario<SettingsActivity> settings = ActivityScenario.launch(SettingsActivity.class)) {
            settings.onActivity(screen -> {
                View root = screen.getWindow().getDecorView();
                assertTrue(hasText(root, screen.getString(R.string.about_optishare)));
                assertTrue(hasText(root, "Download/OptiShare"));
                assertTrue(hasText(root, screen.getString(R.string.privacy_policy)));
                assertTrue(hasText(root, screen.getString(R.string.system_notification_settings)));
                assertFalse(hasText(root, "My security identity"));
                assertFalse(hasText(root, "SmartRoute status"));
            });
        }
    }

    private static boolean hasText(View root, String needle) {
        return findText(root, needle) != null;
    }

    private static boolean hasExactText(View root, String expected) {
        if (root instanceof TextView
                && expected.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasExactText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    private static View findText(View root, String needle) {
        if (root instanceof TextView
                && ((TextView) root).getText().toString().contains(needle)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findText(group.getChildAt(i), needle);
                if (match != null) return match;
            }
        }
        return null;
    }
}
