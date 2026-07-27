package io.github.chada010.reposcout.security;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.github.chada010.reposcout.config.SecurityProperties;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyFilterTest {

    private static final String EXPECTED_KEY = "test-only-key";

    @Test
    void blankConfigDoesNotBlockApi() throws Exception {
        assertPasses("   ", "GET", "/api/repos", null);
    }

    @Test
    void correctHeaderPasses() throws Exception {
        assertPasses(EXPECTED_KEY, "GET", "/api/repos", EXPECTED_KEY);
    }

    @Test
    void missingAndWrongHeadersReturnSafe401Json() throws Exception {
        for (String header : new String[]{null, "wrong-key"}) {
            MockHttpServletResponse response = invoke(EXPECTED_KEY, "GET", "/api/repos", header);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
            assertThat(new ObjectMapper().readValue(response.getContentAsByteArray(), Map.class))
                    .containsEntry("code", "UNAUTHORIZED")
                    .containsEntry("message", "无权访问该接口");
            assertThat(response.getContentAsString()).doesNotContain(EXPECTED_KEY, "wrong-key");
        }
    }

    @Test
    void exactGetHealthIsPublicButOtherHealthRequestsAreProtected() throws Exception {
        assertPasses(EXPECTED_KEY, "GET", "/api/health", null);
        assertThat(invoke(EXPECTED_KEY, "POST", "/api/health", null).getStatus()).isEqualTo(401);
        assertThat(invoke(EXPECTED_KEY, "GET", "/api/health/other", null).getStatus()).isEqualTo(401);
    }

    @Test
    void optionsAndNonApiPathsPass() throws Exception {
        assertPasses(EXPECTED_KEY, "OPTIONS", "/api/repos", null);
        assertPasses(EXPECTED_KEY, "GET", "/docs/index.html", null);
        assertPasses(EXPECTED_KEY, "GET", "/api", null);
    }

    private void assertPasses(String configuredKey, String method, String path, String header)
            throws Exception {
        assertThat(invoke(configuredKey, method, path, header).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse invoke(String configuredKey, String method, String path, String header)
            throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(
                new SecurityProperties(configuredKey), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (header != null) {
            request.addHeader(InternalApiKeyFilter.HEADER_NAME, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(200);
        }
        return response;
    }
}
