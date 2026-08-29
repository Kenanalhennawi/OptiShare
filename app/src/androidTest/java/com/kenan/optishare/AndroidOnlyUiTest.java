package com.kenan.optishare;

import android.Manifest;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

@RunWith(AndroidJUnit4.class)
public class AndroidOnlyUiTest {
    private final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS);
    private final ActivityScenarioRule<V2Activity> activity =
            new ActivityScenarioRule<>(V2Activity.class);

    @Rule public final RuleChain rules = RuleChain.outerRule(permissions).around(activity);

    @Test public void homeAndReceiveExposeOnlyAndroidExperience() {
        onView(withText(containsString("Fast. Private. Resumable.")))
                .check(matches(isDisplayed()));
        onView(withText(containsString("Windows Companion"))).check(doesNotExist());
        onView(withText(containsString("browser / PC"))).check(doesNotExist());

        onView(withText(containsString("RECEIVE"))).perform(click());
        onView(withText("Receive")).check(matches(isDisplayed()));
        onView(withText(containsString("Android-to-Android transfers use authenticated")))
                .check(matches(isDisplayed()));
        onView(withText(containsString("Browser mode"))).check(doesNotExist());
        onView(withText(containsString("Windows"))).check(doesNotExist());
    }
}
