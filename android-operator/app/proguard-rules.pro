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

# MediaPipe protobuf — R8 reports missing class CalculatorProfileProto$CalculatorProfile
# These are referenced by MediaPipe framework but the proto classes are in a separate AAR
# that may not be on the classpath during R8 shrinking. Tell R8 to ignore these.
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.protobuf.**

# TensorFlow Lite (used by MediaPipe internally)
-keep class org.tensorflow.** { *; }
-keep class tflite.** { *; }

# UVCCamera — native JNI calls
-keep class com.serenegiant.usb.** { *; }
-keep class com.serenegiant.common.** { *; }
