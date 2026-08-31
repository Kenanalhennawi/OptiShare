package com.kenan.optishare;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.kenan.optishare.device.DeviceIdentity;
import com.kenan.optishare.device.TrustedDeviceStore;
import com.kenan.optishare.history.TransferHistoryStore;
import com.kenan.optishare.storage.DownloadStore;
import com.kenan.optishare.settings.AppSettings;
import com.kenan.optishare.settings.LocaleSupport;
import com.kenan.optishare.transfer.SenderSessionStore;
import com.kenan.optishare.ui.AvatarView;

import java.io.File;
import java.util.Locale;

/** Responsive settings hub. It intentionally contains no account or cloud profile. */
public final class SettingsActivity extends Activity {
    private static final int REQ_AVATAR_PHOTO=4107;
    private AppSettings settingsStore;
    private DeviceIdentity identity;
    private boolean dark;
    private int background;
    private int surface;
    private int primaryText;
    private int secondaryText;
    private int accent;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleSupport.wrap(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settingsStore = new AppSettings(this);
        identity = new DeviceIdentity(this);
        resolvePalette();
        render();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_AVATAR_PHOTO||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        settingsStore.setAvatarPhotoUri(uri.toString());
        render();
    }

    private void resolvePalette() {
        String selected = settingsStore.theme();
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = AppSettings.THEME_DARK.equals(selected)
                || (AppSettings.THEME_SYSTEM.equals(selected) && systemDark);
        background = dark ? Color.rgb(7, 17, 31) : Color.rgb(244, 248, 252);
        surface = dark ? Color.rgb(12, 42, 69) : Color.WHITE;
        primaryText = dark ? Color.WHITE : Color.rgb(12, 30, 48);
        secondaryText = dark ? Color.rgb(157, 198, 228) : Color.rgb(74, 94, 113);
        accent = settingsStore.highContrast() ? (dark ? Color.YELLOW : Color.rgb(0, 70, 170))
                : Color.rgb(35, 146, 255);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(36));
        int max = dp(840);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, max);
        wrapper.addView(page, new LinearLayout.LayoutParams(width, -2));
        scroll.addView(wrapper, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹", true);
        back.setContentDescription(getString(R.string.back_plain));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(dp(12), 0, 0, 0);
        title.addView(label(getString(R.string.settings), 25, primaryText, true));
        title.addView(label(getString(R.string.settings_subtitle), 12, secondaryText, false));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        addDeviceSection(page);
        addAppearanceSection(page);
        addTransferSection(page);
        addSoundSection(page);
        addNotificationSection(page);
        addStorageSection(page);
        addPrivacySection(page);
        addHelpSection(page);
        addAboutSection(page);
        setContentView(scroll);
    }

    private void addDeviceSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.device_and_visibility);
        LinearLayout profile=new LinearLayout(this);profile.setGravity(Gravity.CENTER_VERTICAL);profile.setPadding(dp(8),dp(10),dp(8),dp(10));
        AvatarView avatar=new AvatarView(this);profile.addView(avatar,new LinearLayout.LayoutParams(dp(68),dp(68)));
        LinearLayout profileCopy=new LinearLayout(this);profileCopy.setOrientation(LinearLayout.VERTICAL);profileCopy.setPadding(dp(14),0,0,0);profileCopy.addView(label(identity.name(),17,primaryText,true));profileCopy.addView(label(getString(R.string.device_profile_summary),12,secondaryText,false));profile.addView(profileCopy,new LinearLayout.LayoutParams(0,-2,1));
        profile.setClickable(true);profile.setFocusable(true);profile.setOnClickListener(v->renameDevice());card.addView(profile,new LinearLayout.LayoutParams(-1,-2));
        row(card, getString(R.string.choose_avatar), getString(R.string.customize_avatar_summary), this::chooseAvatar);
        row(card, getString(R.string.trusted_devices), getString(R.string.trusted_count, new TrustedDeviceStore(this).list().size()), this::openTrustedDevices);
        row(card, getString(R.string.visibility), getString(R.string.receive_screen_only), null);
    }

    private void addAppearanceSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.appearance_and_language);
        choice(card, R.string.theme, themeLabel(), this::chooseTheme);
        choice(card, R.string.language, languageLabel(), this::chooseLanguage);
        toggle(card, R.string.high_contrast, R.string.high_contrast_summary,
                settingsStore.highContrast(), settingsStore::setHighContrast, true);
    }

    private void addTransferSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.sending_and_receiving);
        choice(card, R.string.duplicate_files, duplicateLabel(), this::chooseDuplicatePolicy);
        toggle(card, R.string.resume_after_disconnect, R.string.resume_after_disconnect_summary,
                settingsStore.resumeAfterDisconnect(), settingsStore::setResumeAfterDisconnect, false);
        toggle(card, R.string.continue_after_failure, R.string.continue_after_failure_summary,
                settingsStore.continueAfterFileFailure(), settingsStore::setContinueAfterFileFailure, false);
        toggle(card, R.string.smart_route_setting, R.string.smart_route_setting_summary,
                settingsStore.smartRoute(), settingsStore::setSmartRoute, false);
        toggle(card, R.string.speed_test_large, R.string.speed_test_large_summary,
                settingsStore.speedTestLargeFiles(), settingsStore::setSpeedTestLargeFiles, false);
        toggle(card, R.string.auto_copy_text, R.string.auto_copy_text_summary,
                settingsStore.autoCopyText(), settingsStore::setAutoCopyText, false);
        toggle(card, R.string.open_received_after, R.string.open_received_after_summary,
                settingsStore.openReceivedAfterTransfer(), settingsStore::setOpenReceivedAfterTransfer, false);
        toggle(card, R.string.allow_apk_receive, R.string.allow_apk_receive_summary,
                settingsStore.allowApkReceive(), settingsStore::setAllowApkReceive, false);
    }

    private void addSoundSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.sounds_and_vibration);
        toggle(card, R.string.sounds, R.string.sounds_summary, settingsStore.soundEnabled(), settingsStore::setSoundEnabled, false);
        toggle(card, R.string.completion_sound, R.string.completion_sound_summary, settingsStore.completionSound(), settingsStore::setCompletionSound, false);
        toggle(card, R.string.vibration, R.string.vibration_summary, settingsStore.vibrationEnabled(), settingsStore::setVibrationEnabled, false);
        toggle(card, R.string.request_vibration, R.string.request_vibration_summary, settingsStore.requestVibration(), settingsStore::setRequestVibration, false);
    }

    private void addNotificationSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.notifications);
        row(card, getString(R.string.transfer_notifications), getString(R.string.active_notification_required), this::openNotificationSettings);
        toggle(card, R.string.completion_notifications, R.string.completion_notifications_summary,
                settingsStore.completionNotifications(), settingsStore::setCompletionNotifications, false);
        row(card, getString(R.string.system_notification_settings), getString(R.string.system_notification_settings_summary), this::openNotificationSettings);
    }

    private void addStorageSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.storage_and_history);
        row(card, getString(R.string.open_received_files), "Download/OptiShare", () -> startActivity(new Intent(this, ReceivedFilesActivity.class)));
        toggle(card, R.string.keep_history, R.string.keep_history_summary, settingsStore.keepHistory(), settingsStore::setKeepHistory, false);
        row(card, getString(R.string.clear_transfer_history), getString(R.string.clear_history_summary), this::confirmClearHistory);
        row(card, getString(R.string.clear_pending_transfer), pendingSummary(), this::confirmClearPending);
        row(card, getString(R.string.clean_temporary_files), temporarySize(), this::confirmCleanTemporary);
    }

    private void addPrivacySection(LinearLayout page) {
        LinearLayout card = section(page, R.string.privacy_and_security);
        row(card, getString(R.string.trusted_devices), getString(R.string.manage_trusted_devices), this::openTrustedDevices);
        row(card, getString(R.string.app_permissions), getString(R.string.app_permissions_summary), this::openAppSettings);
        row(card, getString(R.string.privacy_policy), getString(R.string.privacy_policy_summary),
                () -> openUrl("https://github.com/Kenanalhennawi/OptiShare/blob/feature/v2.2-fast-transfer/PRIVACY_POLICY.md"));
        row(card, getString(R.string.security_design), getString(R.string.security_design_summary),
                () -> openUrl("https://github.com/Kenanalhennawi/OptiShare/blob/feature/v2.2-fast-transfer/SECURITY.md"));
    }

    private void addHelpSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.help_and_contact);
        row(card, getString(R.string.how_to_use), getString(R.string.how_to_use_summary), this::showHelp);
        row(card, getString(R.string.report_problem), getString(R.string.report_problem_summary), this::sendSupportEmail);
        row(card, getString(R.string.contact_us), "optishare20@gmail.com", this::sendSupportEmail);
    }

    private void addAboutSection(LinearLayout page) {
        LinearLayout card = section(page, R.string.about_optishare);
        row(card, getString(R.string.version_label), versionName(), null);
        row(card, getString(R.string.developer), "Kenan Alhennawi", null);
        row(card, getString(R.string.open_source_licenses), getString(R.string.open_source_licenses_summary), this::showLicenses);
        row(card, getString(R.string.share_app), getString(R.string.share_app_summary), this::shareApp);
    }

    private LinearLayout section(LinearLayout page, int titleId) {
        TextView heading = label(getString(titleId), 15, accent, true);
        heading.setPadding(dp(4), dp(24), dp(4), dp(8));
        page.addView(heading);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        card.setBackground(round(surface, 18));
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void row(LinearLayout card, String title, String summary, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(13), dp(8), dp(13));
        row.addView(label(title, 15, primaryText, true));
        if (summary != null && !summary.isEmpty()) row.addView(label(summary, 12, secondaryText, false));
        if (action != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> action.run());
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void choice(LinearLayout card, int titleId, String summary, Runnable action) {
        row(card, getString(titleId), summary, action);
    }

    private interface BooleanConsumer { void accept(boolean value); }

    @SuppressWarnings("deprecation")
    private void toggle(LinearLayout card, int titleId, int summaryId, boolean checked,
                        BooleanConsumer changed, boolean rerender) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(4), dp(10));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(label(getString(titleId), 15, primaryText, true));
        copy.addView(label(getString(summaryId), 12, secondaryText, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setChecked(checked);
        control.setContentDescription(getString(titleId));
        control.setOnCheckedChangeListener((button, value) -> { changed.accept(value); if (rerender) { resolvePalette(); render(); } });
        row.addView(control);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renameDevice() {
        EditText input = new EditText(this);
        input.setText(identity.name());
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(R.string.device_name).setView(input)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    try { identity.setName(input.getText().toString()); render(); }
                    catch (Exception error) { toast(error.getMessage()); }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void chooseAvatar(){
        String[] choices={getString(R.string.use_profile_photo),getString(R.string.customize_illustrated_avatar),getString(R.string.remove_profile_photo)};
        new AlertDialog.Builder(this).setTitle(R.string.choose_avatar).setItems(choices,(dialog,which)->{
            if(which==0){
                Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(pick,REQ_AVATAR_PHOTO);
            }else if(which==1){
                settingsStore.setAvatarPhotoUri("");
                showAvatarDesigner();
            }else{
                settingsStore.setAvatarPhotoUri("");
                render();
            }
        }).setNegativeButton(R.string.cancel,null).show();
    }

    private void showAvatarDesigner() {
        LinearLayout editor=new LinearLayout(this);editor.setOrientation(LinearLayout.VERTICAL);editor.setPadding(dp(22),dp(10),dp(22),dp(4));
        AvatarView preview=new AvatarView(this);LinearLayout previewRow=new LinearLayout(this);previewRow.setGravity(Gravity.CENTER);previewRow.addView(preview,new LinearLayout.LayoutParams(dp(150),dp(150)));editor.addView(previewRow);
        LinearLayout first=new LinearLayout(this);first.setOrientation(LinearLayout.HORIZONTAL);
        Button skin=button(getString(R.string.avatar_skin),false);Button hair=button(getString(R.string.avatar_hair),false);Button style=button(getString(R.string.avatar_style),false);
        first.addView(skin,new LinearLayout.LayoutParams(0,dp(48),1));first.addView(hair,new LinearLayout.LayoutParams(0,dp(48),1));first.addView(style,new LinearLayout.LayoutParams(0,dp(48),1));editor.addView(first);
        LinearLayout second=new LinearLayout(this);second.setOrientation(LinearLayout.HORIZONTAL);
        Button backdrop=button(getString(R.string.avatar_background),false);Button glasses=button(getString(R.string.avatar_glasses),false);Button beard=button(getString(R.string.avatar_beard),false);
        second.addView(backdrop,new LinearLayout.LayoutParams(0,dp(48),1));second.addView(glasses,new LinearLayout.LayoutParams(0,dp(48),1));second.addView(beard,new LinearLayout.LayoutParams(0,dp(48),1));editor.addView(second);
        final int[] skinValue={preview.skin()},hairValue={preview.hairColor()},styleValue={preview.hairStyle()},backgroundValue={preview.background()};final boolean[] glassesValue={preview.glasses()},beardValue={preview.beard()};
        Runnable refresh=()->preview.configure(skinValue[0],styleValue[0],hairValue[0],backgroundValue[0],glassesValue[0],beardValue[0]);
        skin.setOnClickListener(v->{skinValue[0]=(skinValue[0]+1)%6;refresh.run();});hair.setOnClickListener(v->{hairValue[0]=(hairValue[0]+1)%6;refresh.run();});style.setOnClickListener(v->{styleValue[0]=(styleValue[0]+1)%5;refresh.run();});backdrop.setOnClickListener(v->{backgroundValue[0]=(backgroundValue[0]+1)%6;refresh.run();});glasses.setOnClickListener(v->{glassesValue[0]=!glassesValue[0];refresh.run();});beard.setOnClickListener(v->{beardValue[0]=!beardValue[0];refresh.run();});
        new AlertDialog.Builder(this).setTitle(R.string.choose_avatar).setView(editor)
                .setPositiveButton(R.string.save,(d,w)->{settingsStore.setAvatarDesign(skinValue[0],styleValue[0],hairValue[0],backgroundValue[0],glassesValue[0],beardValue[0]);render();})
                .setNegativeButton(R.string.cancel,null).show();
    }

    private void chooseTheme() {
        String[] labels = {getString(R.string.follow_system), getString(R.string.dark_mode), getString(R.string.light_mode)};
        String[] values = {AppSettings.THEME_SYSTEM, AppSettings.THEME_DARK, AppSettings.THEME_LIGHT};
        choose(R.string.theme, labels, values, settingsStore.theme(), value -> settingsStore.setTheme(value), true);
    }

    private void chooseLanguage() {
        String[] labels = {getString(R.string.follow_system), "العربية", "English"};
        String[] values = {AppSettings.LANGUAGE_SYSTEM, AppSettings.LANGUAGE_ARABIC, AppSettings.LANGUAGE_ENGLISH};
        choose(R.string.language, labels, values, settingsStore.language(), value -> settingsStore.setLanguage(value), true);
    }

    private void chooseDuplicatePolicy() {
        String[] labels = {getString(R.string.keep_both), getString(R.string.skip_identical)};
        String[] values = {AppSettings.DUPLICATE_KEEP_BOTH, AppSettings.DUPLICATE_SKIP_IDENTICAL};
        choose(R.string.duplicate_files, labels, values, settingsStore.duplicatePolicy(), settingsStore::setDuplicatePolicy, false);
    }

    private void chooseRoute() {
        String[] labels = {getString(R.string.automatic_recommended), getString(R.string.same_wifi), getString(R.string.wifi_direct)};
        String[] values = {AppSettings.ROUTE_AUTOMATIC, AppSettings.ROUTE_LAN, AppSettings.ROUTE_DIRECT};
        choose(R.string.preferred_route, labels, values, settingsStore.preferredRoute(), settingsStore::setPreferredRoute, false);
    }

    private void chooseVisibility() {
        String[] labels = {getString(R.string.receive_screen_only), getString(R.string.five_minutes)};
        String[] values = {AppSettings.VISIBILITY_RECEIVE_ONLY, AppSettings.VISIBILITY_FIVE_MINUTES};
        choose(R.string.visibility, labels, values, settingsStore.visibility(), settingsStore::setVisibility, false);
    }

    private interface StringConsumer { void accept(String value); }
    private void choose(int titleId, String[] labels, String[] values, String selected,
                        StringConsumer change, boolean recreate) {
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) checked = i;
        new AlertDialog.Builder(this).setTitle(titleId).setSingleChoiceItems(labels, checked, (dialog, which) -> {
            change.accept(values[which]); dialog.dismiss();
            if (recreate) recreate(); else render();
        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void openTrustedDevices() {
        Intent intent = new Intent(this, V2Activity.class).putExtra(V2Activity.EXTRA_OPEN_TRUSTED_DEVICES, true);
        startActivity(intent);
    }

    private void openNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void confirmClearHistory() {
        confirm(R.string.clear_transfer_history, R.string.clear_history_confirm, () -> {
            new TransferHistoryStore(this).clear(); toast(getString(R.string.history_cleared));
        });
    }

    private void confirmClearPending() {
        confirm(R.string.clear_pending_transfer, R.string.clear_pending_confirm, () -> {
            new SenderSessionStore(this).clear(); toast(getString(R.string.pending_cleared)); render();
        });
    }

    private void confirmCleanTemporary() {
        confirm(R.string.clean_temporary_files, R.string.clean_temporary_confirm, () -> {
            long before = temporaryBytes(partialRoot());
            new DownloadStore(this).pruneStalePartials();
            long deleted = Math.max(0L, before - temporaryBytes(partialRoot()));
            toast(getString(R.string.temporary_cleaned, humanBytes(deleted))); render();
        });
    }

    private void confirm(int title, int message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(R.string.confirm, (d, w) -> action.run())
                .setNegativeButton(R.string.cancel, null).show();
    }

    private File partialRoot() { return new File(getFilesDir(), "partial"); }

    private long temporaryBytes(File file) {
        if (file == null || !file.exists()) return 0L;
        long bytes = 0L; File[] children = file.listFiles(); if (children == null) return 0L;
        for (File child : children) bytes += child.isDirectory() ? temporaryBytes(child)
                : child.getName().endsWith(".optishare-part") ? child.length() : 0L;
        return bytes;
    }

    private String temporarySize() { return getString(R.string.temporary_files_summary, humanBytes(temporaryBytes(partialRoot()))); }
    private String pendingSummary() { return new SenderSessionStore(this).exists() ? getString(R.string.pending_exists) : getString(R.string.no_pending_exists); }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle(R.string.how_to_use).setMessage(R.string.help_body)
                .setPositiveButton(R.string.ok, null).show();
    }

    private void showLicenses() {
        new AlertDialog.Builder(this).setTitle(R.string.open_source_licenses).setMessage(R.string.licenses_body)
                .setPositiveButton(R.string.ok, null).show();
    }

    private void sendSupportEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:optishare20@gmail.com"));
        intent.putExtra(Intent.EXTRA_SUBJECT, "OptiShare " + versionName() + " support");
        intent.putExtra(Intent.EXTRA_TEXT, "Android " + Build.VERSION.RELEASE + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n");
        try { startActivity(intent); } catch (Exception error) { toast(getString(R.string.no_email_app)); }
    }

    private void shareApp() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "OptiShare — private local file sharing\nhttps://github.com/Kenanalhennawi/OptiShare");
        startActivity(Intent.createChooser(intent, getString(R.string.share_app)));
    }

    private void openUrl(String value) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value))); }
        catch (Exception error) { toast(getString(R.string.no_browser)); }
    }

    private String themeLabel() {
        if (AppSettings.THEME_DARK.equals(settingsStore.theme())) return getString(R.string.dark_mode);
        if (AppSettings.THEME_LIGHT.equals(settingsStore.theme())) return getString(R.string.light_mode);
        return getString(R.string.follow_system);
    }
    private String languageLabel() {
        if (AppSettings.LANGUAGE_ARABIC.equals(settingsStore.language())) return "العربية";
        if (AppSettings.LANGUAGE_ENGLISH.equals(settingsStore.language())) return "English";
        return getString(R.string.follow_system);
    }
    private String duplicateLabel() {
        if (AppSettings.DUPLICATE_ASK.equals(settingsStore.duplicatePolicy())) return getString(R.string.ask_each_time);
        if (AppSettings.DUPLICATE_SKIP_IDENTICAL.equals(settingsStore.duplicatePolicy())) return getString(R.string.skip_identical);
        return getString(R.string.keep_both);
    }
    private String routeLabel() {
        if (AppSettings.ROUTE_LAN.equals(settingsStore.preferredRoute())) return getString(R.string.same_wifi);
        if (AppSettings.ROUTE_DIRECT.equals(settingsStore.preferredRoute())) return getString(R.string.wifi_direct);
        return getString(R.string.automatic_recommended);
    }
    private String visibilityLabel() { return AppSettings.VISIBILITY_FIVE_MINUTES.equals(settingsStore.visibility()) ? getString(R.string.five_minutes) : getString(R.string.receive_screen_only); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "2.2"; }
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024d * 1024d));
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        return bytes + " B";
    }

    private Button button(String value, boolean compact) {
        Button button = new Button(this);
        button.setText(value); button.setTextColor(primaryText); button.setTextSize(compact ? 22 : 14);
        button.setAllCaps(false); button.setBackground(round(surface, 14));
        return button;
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE); return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value == null ? getString(R.string.operation_failed) : value, Toast.LENGTH_LONG).show(); }
}
