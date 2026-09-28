plugins {
    java
}

group = "net.foenui.mc"
// Version dikelola semantic-release: -PreleaseVersion=x.y.z atau file VERSION.txt.
// Fallback 0.0.0-SNAPSHOT untuk build lokal.
version = (findProperty("releaseVersion") as String?)
    ?: rootDir.resolve("VERSION.txt").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    ?: "0.0.0-SNAPSHOT"
description = "FileDownloader - download file via command"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("FileDownloader")
}
