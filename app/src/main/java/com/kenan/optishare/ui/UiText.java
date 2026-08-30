package com.kenan.optishare.ui;

import android.content.Context;

import com.kenan.optishare.R;

/** Localizes programmatic UI text while preserving protocol and diagnostic strings. */
public final class UiText {
    private UiText() { }

    public static String get(Context context, String value) {
        if (value == null) return "";
        int id = resource(value);
        return id == 0 ? value : context.getString(id);
    }

    private static int resource(String value) {
        switch (value) {
            case "‹ Back": return R.string.back;
            case "SEND": return R.string.send;
            case "RECEIVE": return R.string.receive;
            case "Choose content": return R.string.choose_content;
            case "Become visible": return R.string.become_visible;
            case "Fast. Private. Resumable.": return R.string.hero_title;
            case "Send multiple files without Internet. If the link drops, OptiShare resumes from the last verified chunk instead of starting over.": return R.string.hero_subtitle;
            case "Browse by type": return R.string.browse_by_type;
            case "Photos": return R.string.photos;
            case "Videos": return R.string.videos;
            case "Music": return R.string.music;
            case "Apps": return R.string.apps;
            case "Documents": return R.string.documents;
            case "Folder": return R.string.folder;
            case "Text": return R.string.text;
            case "Clipboard": return R.string.clipboard;
            case "Other": return R.string.other;
            case "Received files center →": return R.string.received_files_center;
            case "Recent transfers": return R.string.recent_transfers;
            case "No transfers yet": return R.string.no_transfers;
            case "Privacy by design": return R.string.privacy_by_design;
            case "• No account or cloud required\n• Ephemeral ECDH key exchange\n• AES-256-GCM authenticated transfer\n• SHA-256 verification before publishing files": return R.string.privacy_details;
            case "Pending transfer": return R.string.pending_transfer;
            case "Confirmed progress is saved. Resume from the last verified chunk instead of starting over.": return R.string.pending_transfer_details;
            case "Resume pending transfer →": return R.string.resume_pending_transfer;
            case "Nothing selected yet. Photos and Videos open inside OptiShare; Files opens Android's document picker.": return R.string.nothing_selected;
            case "Transfer queue • drag-free controls keep ordering predictable": return R.string.transfer_queue;
            case "Clear selection": return R.string.clear_selection;
            case "Add selected to queue": return R.string.add_selected_queue;
            case "Searching for receiving phones…": return R.string.searching_receivers;
            case "Verified OptiShare phones appear here automatically. You can also scan the QR shown on the receiving phone.": return R.string.discovery_hint;
            case "Scan receiver QR": return R.string.scan_receiver_qr;
            case "Search again": return R.string.search_again;
            case "Preparing private receiving session…": return R.string.preparing_receiver;
            case "Receive from browser / PC": return R.string.receive_browser_pc;
            case "Keep this screen open while the sender connects. Android-to-Android transfers use authenticated ECDH and AES-GCM encryption.": return R.string.receive_hint;
            case "Stop receiving": return R.string.stop_receiving;
            case "Preparing transfer…": return R.string.preparing_transfer;
            case "Negotiating encrypted session and resume offsets": return R.string.negotiating_session;
            case "Pause transfer": return R.string.pause_transfer;
            case "Resume transfer": return R.string.resume_transfer;
            case "Cancel transfer": return R.string.cancel_transfer;
            case "Done": return R.string.done;
            case "Queue finished": return R.string.queue_finished;
            case "Transfer complete": return R.string.transfer_complete;
            case "Retry failed files →": return R.string.retry_failed_files;
            case "Retry / resume →": return R.string.retry_resume;
            case "Transfer paused": return R.string.transfer_paused;
            case "Connection interrupted": return R.string.connection_interrupted;
            case "Reconnecting automatically": return R.string.reconnecting_automatically;
            case "Secure connection established": return R.string.secure_connection;
            case "Trusted device verified ✓": return R.string.trusted_verified;
            case "Incoming batch": return R.string.incoming_batch;
            case "Transfer could not continue": return R.string.transfer_failed;
            case "Text received & copied ✓": return R.string.text_received;
            case "File verified ✓": return R.string.file_verified;
            case "Speed test complete ✓": return R.string.speed_test_complete;
            case "Speed test could not finish": return R.string.speed_test_failed;
            case "Restoring transfer": return R.string.restoring_transfer;
            case "Connecting over local Wi-Fi": return R.string.connecting_local_wifi;
            case "Using the encrypted OptiShare transport without Internet": return R.string.encrypted_no_internet;
            case "Speed test": return R.string.speed_test;
            case "Send here": return R.string.send_here;
            case "Searching…": return R.string.searching;
            case "Looking for verified OptiShare Android receivers on this network.": return R.string.searching_verified;
            case "Verified OptiShare • encrypted same-Wi-Fi route": return R.string.verified_same_wifi;
            case "Windows Companion • same network": return R.string.windows_same_network;
            case "This device": return R.string.this_device;
            case "Rename device": return R.string.rename_device;
            case "Received content": return R.string.received_content;
            case "Files are sorted in Download/OptiShare. Text and clipboard items arrive as readable .txt files in the Text folder.": return R.string.received_content_details;
            case "Open received files": return R.string.open_received_files;
            case "About OptiShare": return R.string.about_optishare;
            case "Designed & developed by Kenan Alhennawi": return R.string.developed_by;
            case "Installed apps": return R.string.installed_apps;
            case "Add selected apps to queue": return R.string.add_apps_queue;
            case "Standard apps are sent as APK. Apps installed as split packages are bundled as APKS so every required component is preserved.": return R.string.apps_help;
            case "Received files": return R.string.received_files;
            case "Nothing received yet": return R.string.nothing_received;
            case "Files verified by OptiShare will appear here automatically after they are published to Downloads.": return R.string.received_empty_help;
            case "Only local files are listed. Nothing is uploaded to a cloud service.": return R.string.local_files_only;
            case "Open": return R.string.open;
            case "Share": return R.string.share;
            case "Delete": return R.string.delete;
            case "Delete received file?": return R.string.delete_received_title;
            case "Cancel": return R.string.cancel;
            case "Close": return R.string.close;
            case "Save": return R.string.save;
            case "OK": return R.string.ok;
            case "Decline": return R.string.decline;
            case "Confirm": return R.string.confirm;
            case "Trust this device & confirm": return R.string.trust_confirm;
            case "Local connection • No cloud approval server": return R.string.local_approval;
            case "Trusted devices": return R.string.trusted_devices;
            case "Device name": return R.string.device_name;
            case "This name is used inside OptiShare.": return R.string.device_name_help;
            case "Type or paste text to send securely": return R.string.text_hint;
            case "Send text": return R.string.send_text;
            case "Nearby permission required": return R.string.nearby_permission_title;
            case "Allow Nearby Wi‑Fi devices. On Android 12 or older, Android also requires Location permission and Location services for Wi‑Fi Direct discovery.": return R.string.nearby_permission_message;
            case "Back to nearby devices": return R.string.back_nearby;
            case "Waiting for incoming manifest…": return R.string.waiting_manifest;
            case "Not verified as OptiShare • use the verified OptiShare card above or scan the receiver QR": return R.string.unverified_device;
            case "Clipboard is empty": return R.string.clipboard_empty;
            case "Copy some text first, then try again.": return R.string.clipboard_empty_message;
            case "Clipboard has no text": return R.string.clipboard_no_text;
            case "The current clipboard item cannot be sent as text.": return R.string.clipboard_no_text_message;
            case "Clipboard not added": return R.string.clipboard_not_added;
            case "Text not added": return R.string.text_not_added;
            case "Invalid QR": return R.string.invalid_qr;
            case "This is not an OptiShare 2 pairing code.": return R.string.invalid_qr_code;
            case "Pairing information is incomplete.": return R.string.invalid_qr_incomplete;
            case "Nothing to share": return R.string.nothing_to_share;
            case "OptiShare did not receive a file, link or text from the other app.": return R.string.nothing_to_share_message;
            case "No pending outgoing transfer was found.": return R.string.no_pending_transfer;
            case "Wi‑Fi Direct unavailable": return R.string.wifi_direct_unavailable;
            case "This device does not expose Android Wi‑Fi Direct to OptiShare.": return R.string.wifi_direct_unavailable_message;
            case "Folder ready": return R.string.folder_ready;
            case "Folder could not be opened": return R.string.folder_open_failed;
            case "Could not prepare apps": return R.string.apps_prepare_failed;
            case "Could not import shared content": return R.string.shared_import_failed;
            case "No app can open this file type.": return R.string.file_open_failed;
            case "This file cannot be shared right now.": return R.string.file_share_failed;
            case "The file could not be deleted.": return R.string.file_delete_failed;
            case "No trusted devices yet. On the first secure connection choose ‘Trust this device & confirm’.": return R.string.trusted_none;
            case "Android 5 keeps manual six-digit verification. Persistent trust is available on Android 6 and newer.": return R.string.trusted_legacy;
            default: return 0;
        }
    }
}
