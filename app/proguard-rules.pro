# AlpenSync ProGuard/R8 rules.
#
# The pcontacts lesson (plan Section 5): R8 minification can silently break
# vCard parsing and crypto code. Rules below are ported from pcontacts'
# app/proguard-rules.pro (GPL-3.0) @ bf9b0c5, trimmed to the dependencies M1
# actually ships. ez-vcard / Room / WorkManager rules land together with
# those modules (M2+); release-mode testing before every release is plan
# Section 7.

# ---------------------------------------------------------------
# BouncyCastle (org.bouncycastle:bcpg-jdk18on, bcprov-jdk18on)
# ---------------------------------------------------------------
# BC resolves Provider services and algorithm names by reflection.
# Without these rules SRP / OpenPGP fails at runtime with
# NoSuchAlgorithmException on minified builds.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.openpgp.** { *; }
-keep class org.bouncycastle.bcpg.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-keep class org.bouncycastle.cms.** { *; }
-keep class org.bouncycastle.x509.** { *; }
# BC references javax.naming / javax.security.auth for desktop-only
# config lookups, unavailable on Android — suppress the warnings.
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------
# ez-vcard (com.googlecode.ez-vcard:ez-vcard, :module-contacts)
# ---------------------------------------------------------------
# Ported from pcontacts' post-incident app/proguard-rules.pro @ bf9b0c5
# (docs/research/m2-contacts-notes.md Section 5 — the shipped-broken-sync
# incident): ez-vcard parses/writes vCards via reflection; scribes
# reflectively invoke property, parameter, and util constructors/methods.
# Keeping only `io.scribe` + `property` left `parameter`/`util` members
# tree-shaken, so release builds threw NoSuchMethodException on every
# contact while debug builds stayed green.
#
# Deliberately NOT `-keep ezvcard.**`: keeping the root `Ezvcard` facade
# retains its hCard methods, dragging in jsoup + freemarker (→ re2j /
# java.beans / java.rmi / javax.swing, none on Android), which fails R8's
# missing-class check. We only use text vCards; the html path stays
# strippable.
-keep class ezvcard.io.scribe.** { *; }
-keep class ezvcard.property.** { *; }
-keep class ezvcard.parameter.** { *; }
-keep class ezvcard.util.** { *; }
-dontwarn ezvcard.**
# ez-vcard bundles jsoup + freemarker for its (unused) hCard support; those
# reference desktop-JVM classes absent on Android. Silence the dangling
# references — we never call the hCard path, so R8 still strips it.
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn freemarker.**
-dontwarn java.beans.**
-dontwarn java.rmi.**
-dontwarn javax.swing.**
-dontwarn com.sun.org.apache.xml.internal.**

# ---------------------------------------------------------------
# Kotlinx serialization
# ---------------------------------------------------------------
# Generated $$serializer classes are referenced reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    public static **$Companion Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# ---------------------------------------------------------------
# OkHttp / Okio
# ---------------------------------------------------------------
-dontwarn okhttp3.internal.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------
# Retrofit
# ---------------------------------------------------------------
# Retrofit synthesises proxy classes at runtime and inspects generic
# signatures of suspend-function Continuation<T> parameters; R8 full
# mode strips those signatures ("Unable to create converter for class
# java.lang.Object").
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep our API interface, DTOs, and serializer companions whole:
# Retrofit needs full generic signatures for suspend-function return
# types and kotlinx.serialization needs the $$serializer classes.
-keep class app.alpensync.core.api.** { *; }
-dontwarn retrofit2.**

# ---------------------------------------------------------------
# Coroutines
# ---------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**

# ---------------------------------------------------------------
# Tink (via androidx.security:security-crypto → EncryptedSharedPreferences)
# ---------------------------------------------------------------
# Tink references Error Prone annotations that ship as compile-only stubs;
# they are never present or needed at runtime. (Rules as generated by R8 in
# missing_rules.txt.)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# ---------------------------------------------------------------
# Pinned SRP signing key (core:auth resource)
# ---------------------------------------------------------------
# proton_srp_signing_key.asc is loaded via getResourceAsStream at
# login and is packaged into the library AAR's classes.jar (verified:
# it lands in core/auth's merged feature jar). It reaches the APK only
# once the app module depends on :core:auth (M1 acceptance login
# screen) — today the app shell consumes no core module, so the
# release APK intentionally contains no core code at all. Absence at
# runtime fails login CLOSED (NO_SIGNER_KEY), never open; the M1 live
# login test is the packaging checkpoint.

# ---------------------------------------------------------------
# Room (:core:db, M2c)
# ---------------------------------------------------------------
# KSP generates the Impl classes; keep them + the annotated surfaces.
-keep class **_Impl { *; }
-keep @androidx.room.Database public class * { *; }
-keep @androidx.room.Dao class * { *; }

# ---------------------------------------------------------------
# AndroidX WorkManager (:module-contacts periodic poker, M2d)
# ---------------------------------------------------------------
# WorkManager reflectively constructs Workers via the (Context,
# WorkerParameters) constructor.
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------
# Human-verification WebView JS bridge (:app, in-app Code 9001 flow)
# ---------------------------------------------------------------
# The verify.proton.me challenge page calls AndroidInterface.dispatch(...)
# reflectively; R8 must not strip/rename the annotated method.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------
# Sync framework (:module-contacts, M2d)
# ---------------------------------------------------------------
# The system binder invokes our subclasses' constructors.
-keep public class * extends android.content.AbstractThreadedSyncAdapter {
    public <init>(android.content.Context, boolean);
}
-keep public class * extends android.accounts.AbstractAccountAuthenticator {
    public <init>(android.content.Context);
}
