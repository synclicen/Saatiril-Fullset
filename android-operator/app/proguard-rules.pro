# Socket.io
-keep class io.socket.** { *; }
-keep class com.saatiril.operator.data.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# ── MediaPipe Tasks Vision ──
# Must keep all MediaPipe classes — native .so files call into these via JNI.
# R8 would otherwise strip them as "unused" since native code isn't analyzed.
-keep class com.google.mediapipe.** { *; }
-keep class com.mediapipe.** { *; }

# TensorFlow Lite (used by MediaPipe internally)
-keep class org.tensorflow.** { *; }
-keep class tflite.** { *; }

# UVCCamera — native JNI calls
-keep class com.serenegiant.usb.** { *; }
-keep class com.serenegiant.common.** { *; }
