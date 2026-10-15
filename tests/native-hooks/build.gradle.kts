plugins { java }
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io"); content { includeGroup("com.github.HChenX") } }
}

val sdkRoot = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orNull ?: error("Set ANDROID_HOME to Android SDK 36")
val androidJar = file("$sdkRoot/platforms/android-36/android.jar")
require(androidJar.isFile) { "Android 36 SDK is missing: $androidJar" }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

val nativeApi by configurations.creating
val nativeHelpers by configurations.creating
val helperDirectory = layout.buildDirectory.dir("native-helpers")
val extractNativeHelpers = tasks.register("extractNativeHelpers") {
    inputs.files(nativeHelpers)
    outputs.dir(helperDirectory)
    doLast {
        nativeHelpers.files.forEach { artifact ->
            copy {
                from(zipTree(artifact))
                include("classes.jar")
                rename { "${artifact.nameWithoutExtension}-classes.jar" }
                into(helperDirectory)
            }
        }
    }
}
val nativeHelperJars = files(provider { fileTree(helperDirectory) { include("*.jar") } }).builtBy(extractNativeHelpers)
val extractNativeApi = tasks.register<Copy>("extractNativeApi") {
    from(provider { zipTree(nativeApi.singleFile) })
    include("classes.jar")
    into(layout.buildDirectory.dir("native-api"))
}
val nativeApiJar = files(layout.buildDirectory.file("native-api/classes.jar")).builtBy(extractNativeApi)
sourceSets.main {
    java.srcDir("../../library/hook/src/main/java")
    java.include("com/sevtinge/hyperceiler/hook/api/*.java")
}
dependencies {
    nativeApi("io.github.libxposed:api:102.0.0@aar")
    nativeHelpers("io.github.kyuubiran.ezxhelper:xposed-api-101:3.2.0-preview1@aar")
    nativeHelpers("com.github.HChenX:HookTool:3.2.2@aar")
    testImplementation(nativeHelperJars)
    testImplementation("io.github.kyuubiran.ezxhelper:core:3.2.0-preview1@jar")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation(nativeApiJar)
    implementation("org.apache.commons:commons-lang3:3.21.0")
    compileOnly(files(androidJar))
    testImplementation(files(androidJar))
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
