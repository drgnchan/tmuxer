# JSch loads optional crypto implementations reflectively.
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
# Optional desktop integrations shipped by JSch are unavailable and unused on Android.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
