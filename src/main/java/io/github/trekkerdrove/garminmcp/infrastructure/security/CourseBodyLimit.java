package io.github.trekkerdrove.garminmcp.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import static io.github.trekkerdrove.garminmcp.application.dto.CourseData.MAX_HTTP_BYTES;

final class CourseBodyLimit extends OncePerRequestFilter {
    private final PathPatternRequestMatcher path;
    private final Semaphore slots = new Semaphore(4);

    CourseBodyLimit(PathPatternRequestMatcher path) {
        this.path = path;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !path.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_HTTP_BYTES) {
            reject(response, 413, "course_source_too_large");
            return;
        }
        if (!slots.tryAcquire()) {
            reject(response, 503, "course_busy");
            return;
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_HTTP_BYTES + 1);
            if (body.length > MAX_HTTP_BYTES) {
                reject(response, 413, "course_source_too_large");
                return;
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } finally {
            slots.release();
        }
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"" + code + "\",\"data\":null}");
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final ByteArrayInputStream bytes;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            bytes = new ByteArrayInputStream(body);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override public boolean isFinished() { return bytes.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public int read() { return bytes.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The MCP request body is read synchronously.");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
