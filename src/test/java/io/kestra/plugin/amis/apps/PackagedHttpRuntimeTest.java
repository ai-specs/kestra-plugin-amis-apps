package io.kestra.plugin.amis.apps;

import io.kestra.plugin.amis.apps.testing.PackagedHttpRuntimeMain;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PackagedHttpRuntimeTest {
    @Test
    void isolatedJvmRegistersProviderAndValidatesActualRuntimeAssets() throws Exception {
        Path report = Path.of(System.getProperty("amis.contractReports"), "http-runtime");
        Files.createDirectories(report);
        Path jar = Path.of(System.getProperty("amis.pluginJar"));
        String classpath = System.getProperty("amis.hostJar") + File.pathSeparator
            + System.getProperty("amis.testClasses") + File.pathSeparator + jar
            + File.pathSeparator + System.getProperty("amis.testRuntimeClasspath");
        for (String scenario : List.of("disabled", "enabled")) {
            Path output = report.resolve(scenario);
            Files.createDirectories(output);
            Path log = output.resolve("process.log");
            Process process = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, PackagedHttpRuntimeMain.class.getName(), scenario, output.toString(), jar.toString()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "packaged HTTP runtime JVM timeout: " + scenario);
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception failure) { return failure.toString(); }
            });
            assertTrue(Files.exists(output.resolve("result.txt")));
        }
        assertTrue(Files.readString(report.resolve("disabled/result.txt")).contains("providerCount=0"));
        assertTrue(Files.readString(report.resolve("enabled/result.txt")).contains("assets=10"));
    }
}
