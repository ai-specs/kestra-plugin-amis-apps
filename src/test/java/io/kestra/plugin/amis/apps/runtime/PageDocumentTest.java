package io.kestra.plugin.amis.apps.runtime;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.http.cookie.Cookies;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.generator.CsrfTokenGenerator;
import io.micronaut.security.csrf.validator.CsrfTokenValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageDocumentTest {
    @Test
    void renderEscapesBootstrapScriptBreakoutAndRequiresSafeBase() {
        RuntimeAssets assets = mock(RuntimeAssets.class);
        when(assets.entryTemplate()).thenReturn("<html>__AMIS_BASE__ __AMIS_CSRF__ __AMIS_BOOTSTRAP__</html>");
        PageDocument page = new PageDocument(assets, csrf(), generator(), validator());
        String result = page.render("/company.demo/greeting-app/", "token<&", "{\"x\":\"</script><script>alert(1)</script>\"}");
        assertFalse(result.contains("</script>"));
        assertTrue(result.contains("\\u003c/script\\u003e"));
        assertTrue(result.contains("token&lt;&amp;"));
        assertTrue(result.contains("/company.demo/greeting-app/"));
        assertFalse(result.contains("/company.demo/greeting-app/assets/assets/"));
        assertThrows(IllegalArgumentException.class, () -> page.render("https://evil/assets/", "token", "{}"));
        assertThrows(IllegalArgumentException.class, () -> page.render("/company/greeting/assets/", "token", "[]"));
        assertThrows(IllegalArgumentException.class, () -> page.render("/company.demo/greeting-app/../assets/", "token", "{}"));
    }

    @Test
    void responseReusesValidCsrfCookieAndRegeneratesInvalidOrMissingCookie() {
        RuntimeAssets assets = mock(RuntimeAssets.class);
        when(assets.entryTemplate()).thenReturn("__AMIS_BASE____AMIS_CSRF____AMIS_BOOTSTRAP__");
        CsrfConfiguration csrf = csrf();
        CsrfTokenGenerator<HttpRequest<?>> generator = generator();
        CsrfTokenValidator<HttpRequest<?>> validator = validator();
        when(generator.generateCsrfToken(any())).thenReturn("generated");
        PageDocument page = new PageDocument(assets, csrf, generator, validator);
        HttpRequest<?> request = mock(HttpRequest.class);
        Cookies cookies = mock(Cookies.class);
        when(request.getCookies()).thenReturn(cookies);
        when(request.isSecure()).thenReturn(false);
        when(cookies.findCookie("XSRF-TOKEN")).thenReturn(java.util.Optional.empty());
        MutableHttpResponse<?> generated = page.response(request, "/company.demo/greeting-app/", "{}");
        assertEquals("generated", generated.getCookies().findCookie("XSRF-TOKEN").orElseThrow().getValue());
        assertEquals("no-store", generated.header("Cache-Control"));
        assertTrue(generated.header("Content-Security-Policy").contains("default-src 'self'"));
        assertEquals(SameSite.Strict, generated.getCookies().findCookie("XSRF-TOKEN").orElseThrow().getSameSite().orElseThrow());
        verify(generator).generateCsrfToken(request);

        when(validator.validateCsrfToken(any(), eq("existing"))).thenReturn(true);
        HttpRequest<?> withCookie = mock(HttpRequest.class);
        Cookies existingCookies = mock(Cookies.class);
        when(withCookie.getCookies()).thenReturn(existingCookies);
        when(withCookie.isSecure()).thenReturn(false);
        when(existingCookies.findCookie("XSRF-TOKEN")).thenReturn(java.util.Optional.of(Cookie.of("XSRF-TOKEN", "existing")));
        MutableHttpResponse<?> reused = page.response(withCookie, "/company.demo/greeting-app/", "{}");
        assertEquals("existing", reused.getCookies().findCookie("XSRF-TOKEN").orElseThrow().getValue());
        verify(generator, times(1)).generateCsrfToken(any());
    }

    private static CsrfConfiguration csrf() {
        CsrfConfiguration csrf = mock(CsrfConfiguration.class);
        when(csrf.getCookieName()).thenReturn("XSRF-TOKEN");
        return csrf;
    }

    @SuppressWarnings("unchecked")
    private static CsrfTokenGenerator<HttpRequest<?>> generator() { return mock(CsrfTokenGenerator.class); }

    @SuppressWarnings("unchecked")
    private static CsrfTokenValidator<HttpRequest<?>> validator() { return mock(CsrfTokenValidator.class); }
}
