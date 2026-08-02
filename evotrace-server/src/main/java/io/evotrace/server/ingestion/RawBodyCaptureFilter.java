package io.evotrace.server.ingestion;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Captures the raw request body on {@code /open-api/*} before Spring
 * deserializes it, so HMAC signature verification can run over the exact
 * bytes the client signed. The body is buffered once and replayed on every
 * read via {@link ReusableBodyRequestWrapper}; it is also exposed as the
 * request attribute {@link #RAW_BODY_ATTR}.
 */
@Component
public class RawBodyCaptureFilter extends OncePerRequestFilter {

    public static final String RAW_BODY_ATTR = "evotrace.rawBody";

    @Bean
    public FilterRegistrationBean<RawBodyCaptureFilter> rawBodyFilterRegistration(RawBodyCaptureFilter filter) {
        FilterRegistrationBean<RawBodyCaptureFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/open-api/*");
        return registration;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getAttribute(RAW_BODY_ATTR) == null) {
            request = new ReusableBodyRequestWrapper(request);
        }
        filterChain.doFilter(request, response);
    }

    /** Wrapper that buffers the body once and replays it on every read. */
    static class ReusableBodyRequestWrapper extends HttpServletRequestWrapper {

        private final byte[] body;

        ReusableBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
            request.setAttribute(RAW_BODY_ATTR, new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final java.io.ByteArrayInputStream in =
                        new java.io.ByteArrayInputStream(body);

                @Override
                public boolean isFinished() { return in.available() == 0; }

                @Override
                public boolean isReady() { return true; }

                @Override
                public void setReadListener(ReadListener readListener) { /* stream is fully buffered */ }

                @Override
                public int read() { return in.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
