# Keep JNI entry points stable if minification is ever enabled.
-keepclasseswithmembernames class * {
    native <methods>;
}
