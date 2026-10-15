plugins { java }

repositories {
    mavenCentral()
    maven("https://api.xposed.info") { content { includeGroup("de.robv.android.xposed") } }
}

val sdkRoot = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orNull ?: error("Set ANDROID_HOME to an Android SDK containing platforms/android-36/android.jar")
val androidJar = file("$sdkRoot/platforms/android-36/android.jar")
require(androidJar.isFile) { "Android 36 SDK is missing: $androidJar" }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

sourceSets.main {
    java.srcDir("../../library/hook/src/main/java")
    java.include("com/sevtinge/hyperceiler/hook/utils/prefs/RemotePrefsMirror.java")
    java.include("com/sevtinge/hyperceiler/hook/utils/prefs/LegacyPrefsMigration.java")
    java.include("com/sevtinge/hyperceiler/hook/utils/prefs/SharedPrefsXml.java")
    java.include("com/sevtinge/hyperceiler/hook/utils/prefs/PrefsMap.java")
    java.include("com/sevtinge/hyperceiler/hook/utils/prefs/HookPrefsStore.java")
}

sourceSets.test {
    java.srcDir("../../library/hook/src/main/java/com/sevtinge/hyperceiler/hook/utils/prefs")
    java.include("**/*Test.java", "**/*FakeSharedPreferences.java")
    java.include("PrefsUtils.java", "PrefsChangeObserver.java", "PrefType.java")
    java.include("com/sevtinge/hyperceiler/hook/module/base/tool/AppsTool.java")
    java.include("com/sevtinge/hyperceiler/hook/provider/SharedPrefsProvider.java")
    java.include("com/sevtinge/hyperceiler/hook/utils/log/XposedLogUtils.java")
}

dependencies {
    // XML implementation precedes android.jar's unimplemented JVM stubs at test runtime.
    implementation("xpp3:xpp3:1.1.4c")
    compileOnly(files(androidJar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("de.robv.android.xposed:api:82")
    testImplementation(files(androidJar))
}

tasks.test {
    // Gradle orders file dependencies before Maven artifacts; keep Android's XML stubs last.
    classpath = classpath.minus(files(androidJar)).plus(files(androidJar))
    testLogging { events("passed", "failed", "skipped") }
}
