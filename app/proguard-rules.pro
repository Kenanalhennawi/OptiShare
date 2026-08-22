# OptiShare 2 baseline release rules.
# Keep model/protocol names stable while wire compatibility is under active development.
-keep class com.kenan.optishare.protocol.** { *; }
-keep class com.kenan.optishare.model.** { *; }
-dontwarn javax.annotation.**
