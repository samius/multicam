# Keep OkHttp/Okio classes used via reflection; not strictly needed since
# minifyEnabled is false by default, kept here for future hardening.
-dontwarn okhttp3.**
-dontwarn okio.**
