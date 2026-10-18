// AE2 Declogger for GT: New Horizons (Minecraft 1.7.10, Forge 10.13.4, AE2 Unofficial). GTNH's convention plugin
// (RetroFuturaGradle underneath), Java 8. Shared sources and the preprocessor: ../gradle/shared.gradle.
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

extra["legacyMc"] = "1.7.10"
apply(from = "../gradle/shared.gradle")

// -PselfTest: the dev server runs the self-test (selftest/ServerSelfTest) and stops when it is done.
tasks.named<JavaExec>("runServer") {
    if (project.hasProperty("selfTest")) {
        systemProperty("ae2declogger.serverSelfTest", "true")
        systemProperty("ae2declogger.selfTestExit", "true")
    }
}
