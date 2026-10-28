rootProject.name = "qkt-venue-gateway"

include("vgp-wire", "adapter-api", "adapter-testkit", "host", "app")

// Every venue adapter is a directory `adapter-<venue>/` with its own build file: adding one is adding the
// directory, nothing here. The list is published for `app`, which bundles every adapter it names.
val venueAdapters =
    rootDir
        .listFiles { f -> f.isDirectory && f.name.matches(Regex("adapter-[a-z][a-z0-9]*")) && f.resolve("build.gradle.kts").isFile }
        .orEmpty()
        .map { it.name }
        .filter { it != "adapter-api" && it != "adapter-testkit" }
        .sorted()
include(venueAdapters)
gradle.extra["venueAdapters"] = venueAdapters
