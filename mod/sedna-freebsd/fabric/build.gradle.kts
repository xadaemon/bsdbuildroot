repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    "modImplementation"("net.fabricmc:fabric-loader:${property("fabricLoaderVersion")}")
    // Only the resource loader is used (built-in packs); fabric.mod.json
    // depends on just that module.
    "modImplementation"("net.fabricmc.fabric-api:fabric-api:${property("fabricApiVersion")}")
}
