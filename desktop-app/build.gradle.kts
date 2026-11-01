plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web", "javafx.media", "javafx.swing")
}

application {
    mainClass.set("com.roll0the1dice.voxread.desktop.VoxReadAppKt")
}

tasks.withType<Tar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Sync> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

sourceSets {
    main {
        resources {
            srcDir("../readium/navigator/src/main/assets")
        }
    }
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Kotlin Coroutines Swing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // HTML Parser (Jsoup for SpeechMap & Math extraction)
    implementation(libs.jsoup)

    // SQLite JDBC
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")

    // Networking (Edge TTS WebSocket & OPDS)
    implementation(libs.okhttp)

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.12")
}

tasks.register("copyRuntimeLibs") {
    group = "distribution"
    description = "Copies app JAR and all runtimeClasspath JARs without filename collisions."
    dependsOn("jar")
    inputs.files(tasks.named("jar"))
    inputs.files(configurations.getByName("runtimeClasspath"))
    val outDir = layout.buildDirectory.dir("app-libs")
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val appJar = tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile
        appJar.copyTo(File(dir, "000_${appJar.name}"), overwrite = true)
        configurations.getByName("runtimeClasspath").files.forEachIndexed { idx, file ->
            val targetName = if (file.name.startsWith("javafx-")) {
                file.name
            } else {
                "%03d_%s".format(idx + 1, file.name)
            }
            file.copyTo(File(dir, targetName), overwrite = true)
        }
    }
}

tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Packages VoxRead as a self-contained single-file Linux AppImage."
    dependsOn("copyRuntimeLibs")
    workingDir(rootProject.projectDir)
    commandLine("bash", "${rootProject.projectDir}/scripts/package-appimage.sh")
}
