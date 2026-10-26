enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("plugin.lombok") version "2.4.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "N-Zik"
val connectOnly = providers.gradleProperty("connectOnly").orNull == "true"

include(":connect-server")
include(":oldtube")
project(":oldtube").projectDir = file("extensions/innertube")
include(":invidious")
project(":invidious").projectDir = file("extensions/invidious")
include(":ktor-client-brotli")
project(":ktor-client-brotli").projectDir = file("extensions/ktor-client-brotli")

if (!connectOnly) {
    include(":ComposeN-Zik")
    include(":kugou")
    project(":kugou").projectDir = file("extensions/kugou")
    include(":lrclib")
    project(":lrclib").projectDir = file("extensions/lrclib")
    include(":musicbrainz")
    project(":musicbrainz").projectDir = file("extensions/musicbrainz")
    include(":lastfm")
    project(":lastfm").projectDir = file("extensions/lastfm")
    include(":betterlyrics")
    project(":betterlyrics").projectDir = file("modules/betterlyrics")
    include(":discordrpc")
    project(":discordrpc").projectDir = file("modules/discordrpc")
    include(":nextvisualizer")
    project(":nextvisualizer").projectDir = file("modules/nextvisualizer")
}

