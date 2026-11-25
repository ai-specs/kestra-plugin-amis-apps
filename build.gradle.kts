import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { java }

group = "io.kestra.plugin"
version = "0.0.1-SNAPSHOT"

repositories { mavenCentral() }
dependencyLocking { lockAllConfigurations() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

dependencies {
    compileOnly(files(".integration/host-webserver.jar"))
    testImplementation(files(".integration/host-webserver.jar"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyHost = tasks.register<Exec>("verifyHost") {
    commandLine("python3", "scripts/host-evidence.py", "verify")
}
tasks.compileJava { dependsOn(verifyHost) }

tasks.test { useJUnitPlatform() }
tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyThinJar = tasks.register("verifyThinJar") {
    dependsOn(tasks.jar)
    doLast {
        val artifact = tasks.jar.get().archiveFile.get().asFile
        ZipFile(artifact).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            check(entries.any { it.endsWith(".class") }) { "Empty jar" }
            check(entries.filter { it.endsWith(".class") }.all {
                it.startsWith("io/kestra/plugin/amis/apps/domain/")
            }) { "Host or third-party classes in thin jar" }
            check(entries.none { it.endsWith(".jar") }) { "Nested dependency jar" }
            check(entries.none { it.startsWith("META-INF/micronaut/") || it.startsWith("META-INF/services/") }) {
                "Foundation must not register runtime beans or services"
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        val report = layout.buildDirectory.file("reports/thin-jar.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText("artifact=${artifact.name}\nsha256=$hash\nclasses=domain-only\nbeans=none\n")
        logger.lifecycle(report.readText())
    }
}
tasks.check { dependsOn(verifyThinJar) }
