package com.demeter.backend.common.web;

import com.demeter.backend.security.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiRequestSizeFilter extends OncePerRequestFilter {

    private final HttpRequestProperties properties;
    private final SecurityProblemWriter problemWriter;

    public ApiRequestSizeFilter(HttpRequestProperties properties, SecurityProblemWriter problemWriter) {
        this.properties = properties;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.maxBodyBytes()) {
            writeTooLarge(request, response);
            return;
        }
        if (contentLength >= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        filterChain.doFilter(new LimitedBodyRequest(request, properties.maxBodyBytes()), response);
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problemWriter.write(
                request,
                response,
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "The request body exceeds the configured size limit");
    }

    private static final class LimitedBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final long maxBodyBytes;

        private LimitedBodyRequest(HttpServletRequest request, long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
            jakarta.servlet.ServletInputStream delegate = super.getInputStream();
            InputStream limited = new InputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    if (value >= 0 && ++read > maxBodyBytes) {
                        throw new RequestBodyTooLargeException();
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = delegate.read(bytes, offset, length);
                    if (count > 0 && (read += count) > maxBodyBytes) {
                        throw new RequestBodyTooLargeException();
                    }
                    return count;
                }
            };
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    delegate.setReadListener(readListener);
                }

                @Override
                public int read() throws IOException {
                    return limited.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    return limited.read(bytes, offset, length);
                }
            };
        }
    }

    public static final class RequestBodyTooLargeException extends IOException {

        public RequestBodyTooLargeException() {
            super("Request body exceeds the configured size limit");
        }
    }
}
