# Socket.io
-keep class io.socket.** { *; }
-keep class com.saatiril.full.data.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# AutoValue/Javapoet shaded (transitive from Gson/Socket.io)
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**

# MediaPipe Tasks Vision
-keep class com.google.mediapipe.** { *; }
-keep class com.mediapipe.** { *; }

# MediaPipe protobuf — R8 reports missing class
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.protobuf.**

# TensorFlow Lite (used by MediaPipe internally)
-keep class org.tensorflow.** { *; }
-keep class tflite.** { *; }

# UVCCamera — native JNI calls
-keep class com.serenegiant.usb.** { *; }
-keep class com.serenegiant.common.** { *; }
