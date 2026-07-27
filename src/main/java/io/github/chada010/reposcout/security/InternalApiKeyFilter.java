package io.github.chada010.reposcout.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.chada010.reposcout.config.SecurityProperties;
import io.github.chada010.reposcout.controller.dto.ErrorResponse;

/** 对 /api/** 的应用层共享密钥门禁;不是用户认证。 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Repo-Scout-Internal-Key";

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);
    private static final String API_PREFIX = "/api/";

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!StringUtils.hasText(properties.internalApiKey())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(API_PREFIX)) {
            return true;
        }
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        return "GET".equals(request.getMethod()) && "/api/health".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        byte[] expected = properties.internalApiKey().getBytes(StandardCharsets.UTF_8);
        String actualHeader = request.getHeader(HEADER_NAME);
        byte[] actual = actualHeader == null ? new byte[0] : actualHeader.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expected, actual)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("内部 API 门禁拒绝请求: method={}, path={}, remoteAddr={}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse("UNAUTHORIZED", "无权访问该接口"));
    }
}
