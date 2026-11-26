package io.kestra.plugin.amis.apps.testing;

import io.kestra.core.repositories.AuthorizationEvaluationContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.repositories.ExecutionRepositoryInterface;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.runners.FlowMetaStoreInterface;
import io.kestra.core.services.ConditionService;
import io.kestra.core.services.ExecutionOutputService;
import io.kestra.core.services.FlowService;
import io.kestra.core.services.WebhookService;
import io.kestra.webserver.rbac.FlowNamespaceFileReaderV1;
import io.kestra.core.tenant.TenantService;
import io.kestra.plugin.amis.apps.runtime.AmisHttpRouteProvider;
import io.kestra.webserver.rbac.HttpExtensionRouteProviderV1;
import io.kestra.webserver.rbac.RbacConfiguration;
import io.kestra.webserver.rbac.RbacExecutionGate;
import io.kestra.webserver.rbac.RbacGate;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.DefaultApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisherFactory;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.generator.CsrfTokenGenerator;
import io.micronaut.security.csrf.validator.CsrfTokenValidator;
import io.micronaut.inject.BeanConfiguration;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.provider.JakartaProviderBeanDefinition;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PackagedHttpRuntimeMain {
    private static final String PROVIDER_REF = "io.kestra.plugin.amis.apps.runtime.$AmisHttpRouteProvider$Definition";
    private static final String RUNTIME_ROOT = "META-INF/kestra/amis-apps/runtime/";

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path report = Path.of(args[1]);
        Path testedJar = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(report);
        boolean enabled = scenario.equals("enabled");
        try (HttpRuntimeContext context = new HttpRuntimeContext(enabled)) {
            List<String> references = context.referenceNames();
            require(references.contains(PROVIDER_REF), "provider BeanDefinitionReference is not visible");
            if (!enabled) {
                require(context.providers().isEmpty(), "disabled context exposed HTTP provider");
                write(report, "disabled=true\nproviderCount=0\n");
                return;
            }
            List<HttpExtensionRouteProviderV1> providers = context.providers();
            require(providers.size() == 1, "expected one HTTP provider, got " + providers.size() + "; cause=" + context.providerFailure());
            HttpExtensionRouteProviderV1 provider = providers.get(0);
            require(provider.getClass().getProtectionDomain().getCodeSource().getLocation().toURI().equals(testedJar.toUri()), "provider did not load from tested jar");
            require(provider.id().equals("amis-apps"), "unexpected provider id");
            require(provider.match(HttpMethod.GET, "/company.demo/greeting-app/pages/home").isPresent(), "page route missing");
            require(provider.match(HttpMethod.POST, "/company.demo/greeting-app/apis/greet").isPresent(), "API route missing");
            verifyAssets(report);
            write(report, "disabled=false\nproviderCount=1\nproviderClass=" + provider.getClass().getName() + "\nassets=12\n");
        }
    }

    private static void verifyAssets(Path report) throws IOException {
        var loader = PackagedHttpRuntimeMain.class.getClassLoader();
        try (var stream = loader.getResourceAsStream(RUNTIME_ROOT + "runtime-manifest.json")) {
            require(stream != null, "runtime manifest missing");
            var manifest = JacksonMapper.ofJson().readTree(stream);
            require(manifest.size() == 3 && manifest.path("version").intValue() == 1 && manifest.path("entry").asText().equals("index.html"), "runtime manifest header invalid");
            require(manifest.path("assets").size() == 12 && !manifest.path("assets").has("runtime-manifest.json"), "runtime manifest asset count invalid");
            for (var entry = manifest.path("assets").fields(); entry.hasNext();) {
                var descriptor = entry.next();
                String path = descriptor.getKey();
                require(!path.startsWith(".") && descriptor.getValue().path("sha256").asText().matches("[a-f0-9]{64}"), "invalid asset descriptor: " + path);
                try (var assetStream = loader.getResourceAsStream(RUNTIME_ROOT + path)) {
                    require(assetStream != null, "runtime asset missing: " + path);
                    byte[] bytes = assetStream.readAllBytes();
                    require(bytes.length > 0, "runtime asset empty: " + path);
                    require(sha256(bytes).equals(descriptor.getValue().path("sha256").asText()), "runtime asset hash mismatch: " + path);
                }
            }
        }
    }

    private static void write(Path report, String content) throws IOException { Files.writeString(report.resolve("result.txt"), content); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static final class HttpRuntimeContext extends DefaultApplicationContext {
        private final List<BeanDefinitionReference> references = new ArrayList<>();
        private Throwable providerFailure;

        HttpRuntimeContext(boolean enabled) throws Exception {
            super((ApplicationContextConfiguration) ApplicationContext.builder(Map.of("kestra.amis-apps.enabled", enabled))
                .enableDefaultPropertySources(false).environmentPropertySource(false).deduceEnvironment(false)
                .deduceCloudEnvironment(false).environments("amis-http-packaged-test").banner(false)
                .allowEmptyProviders(true));
            if (getClass().getClassLoader().getResource("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/" + PROVIDER_REF) == null) {
                throw new IllegalStateException("missing provider BeanDefinitionReference");
            }
            references.add((BeanDefinitionReference<?>) Class.forName(PROVIDER_REF).getConstructor().newInstance());
            references.add(new ApplicationEventPublisherFactory<>());
            references.add(new JakartaProviderBeanDefinition());
            getEnvironment().addPropertySource(PropertySource.of("amis-http-packaged-test", Map.of("kestra.amis-apps.enabled", enabled)));
            getEnvironment().start();
            require(getEnvironment().getProperty("kestra.amis-apps.enabled", Boolean.class).orElse(!enabled) == enabled, "test enabled property was not installed");
            registerMocks();
            start();
        }

        private void registerMocks() {
            registerSingleton(FlowRepositoryInterface.class, Mockito.mock(FlowRepositoryInterface.class));
            registerSingleton(FlowMetaStoreInterface.class, Mockito.mock(FlowMetaStoreInterface.class));
            registerSingleton(TenantService.class, Mockito.mock(TenantService.class));
            registerSingleton(RbacConfiguration.class, Mockito.mock(RbacConfiguration.class));
            registerSingleton(RbacGate.class, Mockito.mock(RbacGate.class));
            registerSingleton(RbacExecutionGate.class, Mockito.mock(RbacExecutionGate.class));
            registerSingleton(FlowInputOutput.class, Mockito.mock(FlowInputOutput.class));
            registerSingleton(FlowService.class, Mockito.mock(FlowService.class));
            registerSingleton(ConditionService.class, Mockito.mock(ConditionService.class));
            registerSingleton(WebhookService.class, Mockito.mock(WebhookService.class));
            registerSingleton(ExecutionRepositoryInterface.class, Mockito.mock(ExecutionRepositoryInterface.class));
            registerSingleton(ExecutionOutputService.class, Mockito.mock(ExecutionOutputService.class));
            registerSingleton(FlowNamespaceFileReaderV1.class, Mockito.mock(FlowNamespaceFileReaderV1.class));
            registerSingleton(CsrfConfiguration.class, Mockito.mock(CsrfConfiguration.class));
            CsrfTokenGenerator<HttpRequest<Object>> generator = new CsrfTokenGenerator<HttpRequest<Object>>() {
                @Override public String generateCsrfToken(HttpRequest<Object> request) { return "test-csrf"; }
            };
            CsrfTokenValidator<HttpRequest<Object>> validator = new CsrfTokenValidator<HttpRequest<Object>>() {
                @Override public boolean validateCsrfToken(HttpRequest<Object> request, String token) { return true; }
                @Override public int getOrder() { return 0; }
            };
            registerBeanDefinition(RuntimeBeanDefinition.builder((Argument) Argument.of(CsrfTokenGenerator.class, Argument.of(HttpRequest.class, Object.class)), () -> generator).build());
            registerBeanDefinition(RuntimeBeanDefinition.builder((Argument) Argument.of(CsrfTokenValidator.class, Argument.of(HttpRequest.class, Object.class)), () -> validator).build());
        }

        List<String> referenceNames() { return references.stream().map(reference -> reference.getClass().getName()).toList(); }
        List<HttpExtensionRouteProviderV1> providers() {
            try { return List.of(getBean(AmisHttpRouteProvider.class)); }
            catch (Throwable failure) { providerFailure = failure; return List.of(); }
        }
        Throwable providerFailure() { return providerFailure; }
        @Override protected List<BeanDefinitionReference> resolveBeanDefinitionReferences() { return List.copyOf(references); }
        @Override protected List<BeanDefinitionReference> resolveBeanDefinitionReferences(Predicate<BeanDefinitionReference> predicate) { return references.stream().filter(reference -> predicate == null || predicate.test(reference)).toList(); }
        @Override protected Iterable<BeanConfiguration> resolveBeanConfigurations() { return List.of(); }
    }
}
