# Add project specific ProGuard rules here.

# commons-compress
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**
-dontwarn org.apache.commons.codec.**
-dontwarn org.apache.commons.io.**
