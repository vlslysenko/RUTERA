package com.rutera.core;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The client served from the jar (core.config.FrontendConfig): files, cache headers and the SPA fallback.
 * The test classpath carries a stand-in build in {@code static/} (index.html and one asset).
 */
class FrontendTest extends AbstractIntegrationTest {

    /** A browser navigation: HTML preferred, anything else accepted. */
    private MockHttpServletResponse page(String path) throws Exception {
        return mvc.perform(get(path).header("Accept", BROWSER_ACCEPT)).andReturn().getResponse();
    }

    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,*/*;q=0.8";

    private static String text(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void rootIsForwardedToIndexHtml() throws Exception {
        // Boot's welcome page mapping forwards "/"; MockMvc records the forward instead of following it.
        MvcResult root = mvc.perform(get("/").header("Accept", BROWSER_ACCEPT)).andReturn();
        assertThat(root.getResponse().getStatus()).isEqualTo(200);
        assertThat(root.getResponse().getForwardedUrl()).isEqualTo("index.html");
    }

    @Test
    void clientRoutesGetIndexHtmlRevalidatedOnEveryLoad() throws Exception {
        for (String path : new String[] {"/index.html", "/login", "/fleet", "/settings/users", "/fleet/12/"}) {
            MockHttpServletResponse res = page(path);
            assertThat(res.getStatus()).as(path).isEqualTo(200);
            assertThat(res.getContentType()).as(path).startsWith("text/html");
            assertThat(text(res)).as(path).contains("rutera-test-index");
            assertThat(res.getHeader("Cache-Control")).as(path).isEqualTo("no-cache");
        }
    }

    @Test
    void hashedAssetsAreCachedForAYear() throws Exception {
        MockHttpServletResponse res = page("/assets/app-test.js");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(text(res)).contains("rutera-test-asset");
        assertThat(res.getHeader("Cache-Control")).isEqualTo("max-age=31536000, public, immutable");
    }

    @Test
    void missingFilesAre404NotThePage() throws Exception {
        for (String path : new String[] {"/assets/missing.js", "/favicon-missing.svg", "/fleet/report.pdf"}) {
            MockHttpServletResponse res = page(path);
            assertThat(res.getStatus()).as(path).isEqualTo(404);
            assertThat(text(res)).as(path).doesNotContain("rutera-test-index");
        }
    }

    @Test
    void apiAndActuatorPathsNeverGetThePage() throws Exception {
        Client c = client();
        c.login(OWNER_LOGIN, OWNER_PASSWORD);
        MvcResult unknownApi = c.get("/app/no-such-endpoint");
        assertThat(unknownApi.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unknownApi).get("code").asString()).isEqualTo("error.not_found");

        MockHttpServletResponse anonymousApi = page("/app/no-such-endpoint");
        assertThat(anonymousApi.getStatus()).isEqualTo(401);
        assertThat(text(anonymousApi)).doesNotContain("rutera-test-index");

        MockHttpServletResponse health = mvc.perform(get("/actuator/health")).andReturn().getResponse();
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat(text(health)).contains("UP");
    }
}
