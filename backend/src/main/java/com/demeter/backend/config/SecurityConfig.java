package com.demeter.backend.config;

import com.demeter.backend.security.BearerTokenAuthenticationFilter;
import com.demeter.backend.security.SecurityProblemWriter;
import com.demeter.backend.security.ApiRateLimitFilter;
import com.demeter.backend.security.ManagementAccessFilter;
import com.demeter.backend.admin.security.AdminAccessFilter;
import com.demeter.backend.admin.security.AdminLoginRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class SecurityConfig {

    @Bean
    FilterRegistrationBean<BearerTokenAuthenticationFilter> disableBearerFilterAutoRegistration(
            BearerTokenAuthenticationFilter filter) {
        FilterRegistrationBean<BearerTokenAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiRateLimitFilter> disableRateLimitFilterAutoRegistration(ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ManagementAccessFilter> disableManagementFilterAutoRegistration(
            ManagementAccessFilter filter) {
        FilterRegistrationBean<ManagementAccessFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AdminLoginRateLimitFilter> disableAdminRateLimitFilterAutoRegistration(
            AdminLoginRateLimitFilter filter) {
        FilterRegistrationBean<AdminLoginRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<AdminAccessFilter> disableAdminFilterAutoRegistration(AdminAccessFilter filter) {
        FilterRegistrationBean<AdminAccessFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenFilter,
            ApiRateLimitFilter rateLimitFilter,
            ManagementAccessFilter managementAccessFilter,
            AdminAccessFilter adminAccessFilter,
            AdminLoginRateLimitFilter adminLoginRateLimitFilter,
            SecurityProblemWriter problemWriter) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers("/api/**", "/actuator/**"))
                .cors(cors -> cors.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=()")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/wechat/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/admin/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problemWriter.write(
                                request, response, 401, "UNAUTHORIZED", "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) -> problemWriter.write(
                                request, response, 403, "FORBIDDEN", "Access is denied")))
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(managementAccessFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(adminAccessFilter, ManagementAccessFilter.class)
                .addFilterAfter(adminLoginRateLimitFilter, AdminAccessFilter.class)
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }
}
