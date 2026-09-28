plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
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
    // FTP/FTPS client + SFTP client + JSON (di-shade ke dalam jar via shadowJar)
    implementation("commons-net:commons-net:3.13.0")
    implementation("com.github.mwiede:jsch:2.28.7")
    implementation("org.json:json:20260814")
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

// Fat-jar berisi commons-net + jsch + json agar bisa jalan tanpa install lib di server
// dan bisa dijalankan mandiri via `java -jar` (Main-Class = mode standalone).
// Hasil: build/libs/FileDownloader-<version>-all.jar (ini yang di-upload ke GitHub Release)
tasks.shadowJar {
    archiveBaseName.set("FileDownloader")
    archiveClassifier.set("all")
    relocate("org.apache.commons.net", "net.foenui.mc.fileDownloader.libs.commonsnet")
    relocate("org.apache.commons.io", "net.foenui.mc.fileDownloader.libs.commonsio")
    relocate("com.jcraft.jsch", "net.foenui.mc.fileDownloader.libs.jsch")
    relocate("org.json", "net.foenui.mc.fileDownloader.libs.json")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "net.foenui.mc.fileDownloader.cli.StandaloneMain")
    }
}

// Tiap ./gradlew build juga menghasilkan fat-jar
tasks.build {
    dependsOn(tasks.shadowJar)
}
