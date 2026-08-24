package com.demeter.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SecurityProblemWriter {

    private final ObjectMapper objectMapper;

    public SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:demeter:error:" + code.toLowerCase().replace('_', '-'));
        body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        body.put("requestId", MDC.get("requestId"));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
