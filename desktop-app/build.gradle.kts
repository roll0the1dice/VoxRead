plugins {
    scala
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web", "javafx.media", "javafx.swing")
}

application {
    mainClass.set("com.roll0the1dice.voxread.desktop.VoxReadApp")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    // Scala 3
    implementation("org.scala-lang:scala3-library_3:3.3.3")

    // ScalaFX
    implementation("org.scalafx:scalafx_3:21.0.0-R32")

    // AtlantaFX Modern UI Theme
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")

    // Feather Icons for JavaFX / AtlantaFX
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.3.1")

    // SQLite JDBC
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")

    // Networking (Edge TTS WebSocket & OPDS)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.12")
}

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-indent", "-rewrite")
}
