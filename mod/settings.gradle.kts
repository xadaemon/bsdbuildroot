pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { maven("https://maven.architectury.dev") }
            filter {
                includeGroup("architectury-plugin")
                includeGroupByRegex("dev\\.architectury.*")
                includeGroup("com.mojang")
            }
        }
        exclusiveContent {
            forRepository { maven("https://maven.fabricmc.net") }
            filter {
                includeGroupByRegex("net\\.fabricmc.*")
                includeGroup("fabric-loom")
            }
        }
        maven("https://maven.neoforged.net/releases")
        gradlePluginPortal()
    }
}

rootProject.name = "oc2-freebsd"

// The mods, each for both loaders; see build.gradle.kts.
for (mod in listOf("sedna-freebsd", "power-bsd")) {
    include(":$mod:fabric", ":$mod:neoforge")
}
