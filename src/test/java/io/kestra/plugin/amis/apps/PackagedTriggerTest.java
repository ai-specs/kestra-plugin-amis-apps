package io.kestra.plugin.amis.apps;

import io.kestra.plugin.amis.apps.testing.PackagedTriggerMain;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackagedTriggerTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "no-service", "present"})
    void isolatedJvmConsumesOnlyThePublishedHostAndActualThinJar(String scenario) throws Exception {
        Path report = Path.of(System.getProperty("amis.contractReports"), scenario);
        Files.createDirectories(report);
        Path artifact = Path.of(System.getProperty("amis.pluginJar"));
        Path tested = artifact;
        if (scenario.equals("no-service")) {
            tested = report.resolve("plugin-with-introspection-but-no-service.jar");
            try (ZipFile input = new ZipFile(artifact.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(tested))) {
                for (var entry : input.stream().toList()) {
                    if (!entry.getName().equals("META-INF/services/io.kestra.core.models.Plugin")) {
                        output.putNextEntry(entry);
                        if (!entry.isDirectory()) {
                            output.write(input.getInputStream(entry).readAllBytes());
                        }
                        output.closeEntry();
                    }
                }
            }
        }
        String classpath = System.getProperty("amis.hostJar") + File.pathSeparator + System.getProperty("amis.testClasses");
        if (!scenario.equals("absent")) {
            classpath += File.pathSeparator + tested;
        }
        Path log = report.resolve("process.log");
        Process process = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, PackagedTriggerMain.class.getName(), scenario, report.toString(), tested.toString()))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            fail("Packaged model JVM exceeded timeout");
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(log);
            } catch (java.io.IOException failure) {
                return failure.toString();
            }
        });
        assertTrue(Files.exists(report.resolve("result.json")));
    }
}
