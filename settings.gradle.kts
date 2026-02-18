pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://repo1.maven.org/maven2") }
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://maven.twilio.com") }
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }

    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://repo1.maven.org/maven2") }
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
    }
}

rootProject.name = "MyApplication"
include(":app")
