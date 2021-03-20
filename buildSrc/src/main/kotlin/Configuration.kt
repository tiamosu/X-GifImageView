@file:Suppress("unused", "SpellCheckingInspection")

object Android {
    const val compileSdkVersion = 30
    const val buildToolsVersion = "30.0.3"
    const val minSdkVersion = 15
    const val targetSdkVersion = 30

    const val versionName = "1.0"
    const val versionCode = 1
}

object Versions {
    const val kotlin = "1.4.31"
    const val appcompat = "1.2.0"
    const val annotation = "1.1.0"

    const val okhttp3 = "4.9.0"
    const val common_io = "2.6"
}

object Publish {
    const val groupId = "com.github.tiamosu"
}

object Deps {
    const val androidx_appcompat = "androidx.appcompat:appcompat:${Versions.appcompat}"
    const val androidx_annotation = "androidx.annotation:annotation:${Versions.annotation}"

    const val okhttp3 = "com.squareup.okhttp3:okhttp:${Versions.okhttp3}"
    const val commons_io = "commons-io:commons-io:${Versions.common_io}"
}