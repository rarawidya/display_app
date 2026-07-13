# R8 / ProGuard rules for the release build (isMinifyEnabled + isShrinkResources).
#
# The app is deliberately reflection-light: telemetry uses a hand-written Cap'n Proto
# codec (no reflection), JSON uses org.json (platform), and Room/Compose/coroutines/
# MapLibre all ship their own consumer rules. So the defaults do most of the work and
# only the seams below need explicit keeps.

# ── Crash readability ────────────────────────────────────────────────────────
# Keep line numbers so obfuscated release stack traces stay deobfuscatable against
# the generated mapping.txt, and hide the real source-file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Enums decoded from persisted ordinals ────────────────────────────────────
# VehicleMode (and friends) are reconstructed from stored ordinals via `entries` /
# `values()`; keep enum members so R8 can't rename/strip values it doesn't see
# referenced by name.
-keepclassmembers enum com.example.displayapp.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Room ─────────────────────────────────────────────────────────────────────
# Room generates its own code + ships consumer rules; keep entities as an extra guard
# (schema columns are matched by field name).
-keep class com.example.displayapp.data.persistence.entity.** { *; }

# BluetoothGatt#refresh() is invoked by name via reflection, but the target is a
# framework class (android.bluetooth.BluetoothGatt) which R8 never touches — no keep
# needed here; noted so a future reader doesn't add a redundant rule.
