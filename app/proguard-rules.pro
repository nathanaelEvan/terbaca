# Terbaca ProGuard Rules

# Shrink and optimize, but keep class/member names: the baseline profile (baseline-prof.txt)
# and crash stack traces stay readable without a mapping file.
-dontobfuscate

# JNI entry points are looked up by name from libterbaca_pdfium.so
# (Java_app_terbaca_core_pdfium_PdfiumNative_*).
-keepclasseswithmembers class app.terbaca.core.pdfium.PdfiumNative {
    native <methods>;
}
