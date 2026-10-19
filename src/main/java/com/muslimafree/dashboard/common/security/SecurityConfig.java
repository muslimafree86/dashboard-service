package com.muslimafree.dashboard.common.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.exception.ProblemDetailFactory;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless resource server. This service issues no tokens: it accepts the RS256 access tokens of
 * user-profile-managment (same issuer, its public key / JWKS) and, like that service, rejects tokens whose session was
 * revoked. Every discovery endpoint requires a signed-in member; there is no anonymous feed.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_GET = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health/**", "/actuator/info"};

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler, DiscoveryRateLimiter rateLimiter, ProblemDetailFactory problems,
            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // token roles are not expanded by a hierarchy: an admin or manager token may not carry MODERATOR
                        .requestMatchers("/api/v1/dashboard/admin/**").hasAnyRole(CurrentUser.STAFF_ROLES)
                        .requestMatchers("/api/v1/dashboard/**").hasRole("MEMBER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                // created here, not as a bean, so it runs once, inside the chain, after the token was validated
                .addFilterAfter(new DiscoveryRateLimitFilter(rateLimiter, problems, objectMapper),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Signature (RS256 with user-profile-managment's key), expiry, issuer, and a live session. {@code @Lazy} breaks the
     * cycle decoder -> session validator -> data source -> security.
     */
    @Bean
    public JwtDecoder jwtDecoder(AppProperties properties, ResourceLoader resourceLoader,
            @Lazy SessionValidator sessions) {
        AppProperties.Security security = properties.security();
        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(security.jwkSetUri())) {
            decoder = NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        } else if (StringUtils.hasText(security.publicKeyLocation())) {
            decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(resourceLoader, security.publicKeyLocation()))
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            throw new IllegalStateException("Set JWT_JWK_SET_URI (user-profile-managment's /.well-known/jwks.json) "
                    + "or JWT_PUBLIC_KEY_LOCATION (its PEM public key)");
        }
        OAuth2TokenValidator<Jwt> sessionAlive = token -> {
            try {
                if (sessions.isUsable(CurrentUser.from(token))) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (IllegalArgumentException malformedClaim) {
                // sub / sid that are not UUIDs: same answer as a revoked session
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session is no longer valid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(security.issuer()), sessionAlive));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.web().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT_LANGUAGE));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ProblemDetailFactory problems, ObjectMapper objectMapper) {
        return (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, problems.create(ErrorCode.UNAUTHENTICATED), objectMapper);
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(ProblemDetailFactory problems, ObjectMapper objectMapper) {
        return (request, response, ex) -> write(response, problems.create(ErrorCode.ACCESS_DENIED), objectMapper);
    }

    private static void write(HttpServletResponse response, ProblemDetail problem, ObjectMapper objectMapper)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Token roles are not expanded by a hierarchy: staff tokens carry MEMBER too (MEMBER is every account's role). */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(CurrentUser.ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        return roles.stream().<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }

    /** X.509 PEM, the format user-profile-managment's JWT_PUBLIC_KEY_LOCATION uses. */
    private static RSAPublicKey readPublicKey(ResourceLoader resourceLoader, String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load the JWT public key from " + location, e);
        }
    }
}
