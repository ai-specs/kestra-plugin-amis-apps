package io.kestra.plugin.amis.apps.runtime;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.generator.CsrfTokenGenerator;
import io.micronaut.security.csrf.validator.CsrfTokenValidator;
import java.util.Objects;

public final class PageDocument {
    private final RuntimeAssets assets;
    private final CsrfConfiguration csrf;
    private final CsrfTokenGenerator<HttpRequest<?>> generator;
    private final CsrfTokenValidator<HttpRequest<?>> validator;

    public PageDocument(RuntimeAssets assets, CsrfConfiguration csrf, CsrfTokenGenerator<HttpRequest<?>> generator,
                        CsrfTokenValidator<HttpRequest<?>> validator) {
        this.assets = Objects.requireNonNull(assets);
        this.csrf = Objects.requireNonNull(csrf);
        this.generator = Objects.requireNonNull(generator);
        this.validator = Objects.requireNonNull(validator);
    }

    public MutableHttpResponse<?> response(HttpRequest<?> request, String baseUrl, String bootstrapJson) {
        String token = request.getCookies().findCookie(csrf.getCookieName()).map(Cookie::getValue)
            .filter(value -> validator.validateCsrfToken(request, value)).orElseGet(() -> generator.generateCsrfToken(request));
        if (token == null || token.isBlank()) throw new IllegalStateException("CSRF token generation is unavailable");
        return HttpResponse.ok(render(baseUrl, token, bootstrapJson)).contentType("text/html; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'")
            .cookie(Cookie.of(csrf.getCookieName(), token).path("/").httpOnly(true).secure(request.isSecure()).sameSite(SameSite.Strict));
    }

    public String render(String baseUrl, String token, String bootstrapJson) {
        String[] segments = baseUrl == null ? new String[0] : baseUrl.split("/", -1);
        if (segments.length != 4 || !segments[0].isEmpty() || !segments[3].isEmpty()) {
            throw new IllegalArgumentException("Invalid runtime asset base");
        }
        io.kestra.plugin.amis.apps.domain.Identifiers.namespace(segments[1]);
        io.kestra.plugin.amis.apps.domain.Identifiers.flowId(segments[2]);
        new JsonInputParser(65536, 16).parse(bootstrapJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String safeJson = bootstrapJson.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        return assets.entryTemplate().replace("__AMIS_BASE__", escape(baseUrl))
            .replace("__AMIS_CSRF__", escape(Objects.requireNonNull(token))).replace("__AMIS_BOOTSTRAP__", safeJson);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
