//
// The images as mods, for Fabric and NeoForge: each mod, <name>/, is the
// board's data pack (../board/<board>/datapack) with the disk image,
// registered as a built-in data pack that is turned on in every world (and
// can be turned off with /datapack).
//
//   sedna-freebsd   Sedna FreeBSD, for the RISC-V computers
//   power-bsd       Power BSD, for the tier 5 (PowerPC) computer
//
// Each mod's settings are <name>.<setting> in gradle.properties: the board,
// the image and where `dist` puts the jars.
//
// A mod's version is <release>-build-<number>: the FreeBSD release on the
// image it bundles, read from the image, and the image's build number, which
// the image's build counts up with every image and writes to build-number
// next to it.
//
//   ./gradlew :<name>:dist [-P<name>.image=...] [-P<name>.dist=DIR] [-PbuildNumber=N]
//

import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("dev.architectury.loom") version "1.17-SNAPSHOT" apply false
}

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()

// The release of what the image boots: "@(#)FreeBSD 15.1-RELEASE-p6" in the
// kernel (sys/conf/newvers.sh), which the Sedna image holds uncompressed in
// its boot area. Images with a compressed kernel have the release in
// freebsd-version(1) instead: USERLAND_VERSION="15.1-RELEASE".
fun imageRelease(image: File): String {
    val text = String(image.readBytes(), Charsets.ISO_8859_1)
    for (pattern in listOf(
        """@\(#\)FreeBSD ([0-9]+\.[0-9]+-[A-Za-z0-9-]+)""",
        """USERLAND_VERSION="([0-9]+\.[0-9]+-[A-Za-z0-9-]+)"""",
    )) {
        val releases = Regex(pattern).findAll(text).map { it.groupValues[1] }.toSet()
        if (releases.size > 1) {
            throw GradleException("$image: expected one release, found $releases")
        }
        releases.singleOrNull()?.let { return it }
    }
    throw GradleException("$image: no version string")
}

// The mods: the projects directly below this one. Their Fabric and NeoForge
// projects are below them.
subprojects.filter { it.parent == rootProject }.forEach { mod ->
    val name = mod.name
    fun setting(key: String) = providers.gradleProperty("$name.$key").orNull
        ?: throw GradleException("no $name.$key in gradle.properties")

    val modId = name.replace('-', '_')
    val board = setting("board")
    val image = file(setting("image"))
    val release = if (image.isFile) imageRelease(image) else "unknown"
    // The image's build number: build-number next to the image, or -PbuildNumber.
    val buildNumberFile = File(image.parentFile, "build-number")
    val buildNumber: String? = providers.gradleProperty("buildNumber").orNull
        ?: buildNumberFile.takeIf { it.isFile }?.readText()?.trim()
    val modVersion = "$release-build-${buildNumber ?: "unknown"}"
    val pack = rootProject.file("../board/$board/datapack")
    // The block device's description, <system>.json: the image goes next to
    // it, as <system>.bin. The data pack is at resourcepacks/<system> inside
    // the jars (where Fabric looks for a mod's built-in packs; the NeoForge
    // mods use the same path), which is what the mods' classes register.
    val devices = File(pack, "data/$modId/block_devices/hdd").listFiles { f -> f.name.endsWith(".json") }
    val system = devices?.singleOrNull()?.nameWithoutExtension
    val packDir = "resourcepacks/$system"
    val hddDir = "$packDir/data/$modId/block_devices/hdd"

    mod.subprojects.forEach { loader ->
        loader.apply(plugin = "java")
        loader.apply(plugin = "dev.architectury.loom")

        loader.version = modVersion
        loader.group = modId
        loader.extensions.configure<BasePluginExtension> {
            archivesName.set("$name-MC$minecraftVersion-${loader.name}")
        }

        val loom = loader.extensions.getByType<LoomGradleExtensionAPI>()
        loom.silentMojangMappingsLicense()

        loader.dependencies {
            "minecraft"("com.mojang:minecraft:$minecraftVersion")
            "mappings"(loom.officialMojangMappings())
        }

        // Minecraft 1.21.1 runs on Java 21; any newer JDK builds.
        loader.tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
        }

        loader.tasks.named<ProcessResources>("processResources") {
            doFirst {
                if (!image.isFile) {
                    throw GradleException("no disk image at $image: build it (make BOARD=$board), or pass -P$name.image=...")
                }
                if (buildNumber == null || !buildNumber.matches(Regex("[0-9]+"))) {
                    throw GradleException("no build number for $image (${buildNumber ?: "no $buildNumberFile"}): rebuild the image, or pass -PbuildNumber=N")
                }
                if (system == null) {
                    throw GradleException("$pack: want one data/$modId/block_devices/hdd/*.json")
                }
            }
            inputs.property("version", modVersion)
            inputs.property("minecraftVersion", minecraftVersion)
            filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
                expand("version" to modVersion, "minecraftVersion" to minecraftVersion)
            }
            from(pack) {
                into(packDir)
                exclude("**/.DS_Store")
            }
            from(image) {
                into(hddDir)
                rename { "$system.bin" }
            }
            from(rootProject.file("../LICENSE"))
        }
    }

    // The release jars, in one place.
    mod.tasks.register<Sync>("dist") {
        group = "build"
        description = "Builds the Fabric and NeoForge jars and copies them to $name.dist."
        mod.subprojects.forEach { from(it.tasks.named("remapJar")) }
        into(file(setting("dist")))
    }

    mod.tasks.register("printVersion") {
        group = "help"
        description = "Prints the mod version: the image's release and build number."
        doLast { println(modVersion) }
    }
}
