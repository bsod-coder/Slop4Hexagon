# The release build keeps shrinking disabled (see app/build.gradle.kts): this
# file exists so the config is complete, and documents why nothing is needed.
#
# The JNI surface is reached only by name from the native side, so if R8 is ever
# enabled these must survive:
-keep class com.slop4hexagon.app.nativebridge.SlopNativeBridge { *; }
-keep class com.slop4hexagon.app.nativebridge.QnnNativeProbe { *; }
# Model/benchmark JSON round-tripping uses explicit field access, not
# reflection, so no further keeps are required.
