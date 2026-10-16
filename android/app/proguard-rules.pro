# R8 rules for release builds. See "Release builds and R8" in android/README.md.
#
# This file is deliberately short. Everything reflective in this app is covered
# by rules its libraries ship themselves, and each was checked against the
# release dex rather than assumed:
#
# - kotlinx-serialization: every @Serializable model's generated serializer.
#   Json.decodeFromString<T>() is resolved at compile time by the plugin, so no
#   serializer is looked up by name at run time.
# - Tink (under androidx.security's EncryptedSharedPreferences): its shaded
#   protobuf-lite reads message fields by name, and tink-android's
#   META-INF/proguard/protobuf.pro keeps them. Without that the token store
#   could not parse its own keyset and every sign-in would be memory-only.
# - androidx.lifecycle: viewModel() without a factory instantiates the
#   ViewModel through its no-argument constructor, reflectively, and
#   lifecycle-viewmodel keeps <init>() on every ViewModel subclass.
# - OkHttp, coroutines, Compose, navigation: their own consumer rules.
# - The Activity, Service and both Receivers are named in the manifest, and AAPT
#   generates keep rules for every class the manifest names.
#
# Nothing parses a server string into an enum (JobStatus.state and
# Subscription.status stay Strings), so no enum needs keeping by name.

# Tink is compiled against Error Prone's annotations, which are compile-time
# only (CLASS retention) and not shipped to Android. R8 reports them as missing
# classes; there is nothing to keep, so the warnings are silenced instead.
-dontwarn com.google.errorprone.annotations.**

# :patch-engine's commons-compress references optional codecs (zstd, xz, brotli)
# that are not on the classpath; the engine only reads deflate/stored zips, so
# the classes are never loaded. Listed per the plan; R8 reported no missing
# classes when this was added because nothing in :app called the engine yet -
# re-check once it does (the minified staging run on a device is the proof).
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
