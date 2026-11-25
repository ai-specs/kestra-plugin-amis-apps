import java.util.zip.ZipFile

plugins { java }

group = "io.kestra.plugin"
version = "0.0.1-SNAPSHOT"

repositories { mavenCentral() }
dependencyLocking { lockAllConfigurations() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
layout.buildDirectory.set(layout.projectDirectory.dir(providers.environmentVariable("AMIS_BUILD_DIR").orElse("build").get()))

dependencies {
    annotationProcessor("io.micronaut:micronaut-inject-java:4.10.33") { isTransitive = false }
    annotationProcessor(files(".integration/host-webserver.jar"))
    compileOnly(files(".integration/host-webserver.jar"))
    testImplementation(files(".integration/host-webserver.jar"))
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyHost = tasks.register<Exec>("verifyHost") {
    commandLine("python3", "scripts/host-evidence.py", "verify")
    doLast {
        ZipFile(file(".integration/host-webserver.jar")).use { archive ->
            for (component in listOf("micronaut-core", "micronaut-inject", "micronaut-core-processor")) {
                val descriptor = archive.getInputStream(archive.getEntry("META-INF/maven/io.micronaut/$component/pom.xml"))
                    .bufferedReader().use { it.readText() }
                check(descriptor.contains("<version>4.10.33</version>")) { "Annotation processor differs from formal host: $component" }
            }
        }
    }
}
tasks.compileJava {
    dependsOn(verifyHost)
    options.compilerArgs.addAll(listOf("-processor",
        "io.micronaut.annotation.processing.TypeElementVisitorProcessor,io.micronaut.annotation.processing.BeanDefinitionInjectProcessor"))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("amis.hostJar", file(".integration/host-webserver.jar").absolutePath)
    systemProperty("amis.pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("amis.testClasses", sourceSets.test.get().output.classesDirs.asPath)
    systemProperty("amis.testRuntimeClasspath", configurations.testRuntimeClasspath.get().asPath)
    systemProperty("amis.contractReports", layout.buildDirectory.dir("reports/plugin-contract").get().asFile.absolutePath)
}
val installRuntimeDependencies = tasks.register<Exec>("installRuntimeDependencies") {
    workingDir("ui")
    commandLine("npm", "ci", "--ignore-scripts")
    inputs.files("ui/package.json", "ui/package-lock.json", "ui/.npmrc")
    outputs.file("ui/node_modules/.package-lock.json")
}
val buildRuntime = tasks.register<Exec>("buildRuntime") {
    dependsOn(installRuntimeDependencies, verifyHost)
    workingDir("ui")
    commandLine("npm", "run", "build")
    inputs.dir("ui/src")
    inputs.dir("ui/scripts")
    inputs.files("ui/index.html", "ui/vite.config.mjs", "ui/package.json", "ui/package-lock.json",
        "host-contract.properties", ".integration/host-provenance.json", ".integration/host-webserver.jar")
    outputs.dir("ui/dist")
}
val testRuntime = tasks.register<Exec>("testRuntime") {
    dependsOn(buildRuntime)
    workingDir("ui")
    commandLine("npm", "test")
}
val verifyRuntimeTranslations = tasks.register<Exec>("verifyRuntimeTranslations") {
    dependsOn(buildRuntime)
    workingDir("ui")
    commandLine("npm", "run", "test:translations")
}
tasks.jar {
    dependsOn(buildRuntime)
    from("ui/dist") { into("META-INF/kestra/amis-apps/runtime") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyThinJar = tasks.register<Exec>("verifyThinJar") {
    dependsOn(tasks.jar, tasks.testClasses)
    commandLine("python3", "scripts/verify-thin-jar.py",
        tasks.jar.get().archiveFile.get().asFile.absolutePath,
        file(".integration/host-webserver.jar").absolutePath,
        sourceSets.test.get().output.classesDirs.asPath,
        layout.buildDirectory.dir("reports").get().asFile.absolutePath)
}
tasks.check { dependsOn(verifyThinJar, testRuntime, verifyRuntimeTranslations) }
