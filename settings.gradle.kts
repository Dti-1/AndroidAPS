pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.google.gms.google-services")          version "4.4.4"
        id("com.google.firebase.crashlytics")         version "3.0.6"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "AndroidAPS"

include(":benchmark")
include(":core:data")
include(":core:graph")
include(":core:graphview")
include(":core:interfaces")
include(":core:keys")
include(":core:libraries")
include(":core:nssdk")
include(":core:objects")
include(":core:ui")
include(":core:utils")
include(":core:validators")
include(":database:impl")
include(":database:persistence")
include(":implementation")
include(":plugins:aps")
include(":plugins:automation")
include(":plugins:configuration")
include(":plugins:constraints")
include(":plugins:insulin")
include(":plugins:main")
include(":plugins:sensitivity")
include(":plugins:smoothing")
include(":plugins:source")
include(":plugins:sync")
include(":pump:combov2")
include(":pump:combov2:comboctl")
include(":pump:common")
include(":pump:dana")
include(":pump:danar")
include(":pump:danars")
include(":pump:diaconn")
include(":pump:eopatch")
include(":pump:eopatch:core")
include(":pump:equil")
include(":pump:insight")
include(":pump:medtronic")
include(":pump:medtrum")
include(":pump:omnipod:common")
include(":pump:omnipod:dash")
include(":pump:omnipod:eros")
include(":pump:rileylink")
include(":pump:virtual")
include(":shared:impl")
include(":shared:tests")
include(":ui")
include(":wear")
include(":workflow")
