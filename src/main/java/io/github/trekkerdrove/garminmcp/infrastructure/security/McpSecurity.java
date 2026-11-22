package io.github.trekkerdrove.garminmcp.infrastructure.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
class McpSecurity {

    private static final String METADATA = "/.well-known/oauth-protected-resource";
    private static final String MCP = "/mcp";
    private static final PathPatternRequestMatcher MCP_REQUEST = PathPatternRequestMatcher.pathPattern(MCP);

    private final String issuer;
    private final String ownerSubject;
    private final String publicUrl;
    private final String publicOrigin;

    McpSecurity(
            @Value("${mcp.public-url}") String publicUrl,
            @Value("${mcp.oauth.issuer}") String issuer,
            @Value("${mcp.oauth.owner-subject}") String ownerSubject) {
        this.publicUrl = publicUrl;
        this.issuer = issuer;
        this.ownerSubject = ownerSubject;
        if (configured()) {
            URI resource = httpsUrl(publicUrl);
            httpsUrl(issuer);
            if (!MCP.equals(resource.getRawPath())) {
                throw new IllegalArgumentException("MCP_PUBLIC_URL must end in /mcp");
            }
            this.publicOrigin = "https://" + resource.getRawAuthority();
        } else {
            this.publicOrigin = null;
        }
    }

    @Bean
    RestTemplate oauthHttp() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(factory);
    }

    @Bean
    SecurityFilterChain mcpFilterChain(HttpSecurity http, RestTemplate oauthHttp,
            @Value("${garmin.courses.enabled:false}") boolean courses,
            @Value("${garmin.courses.routing.url:}") String routing) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/health", METADATA).permitAll()
                        .requestMatchers(MCP_REQUEST).access((authentication, request) -> new AuthorizationDecision(
                                configured() && authentication.get() instanceof JwtAuthenticationToken jwt
                                        && ownerSubject.equals(jwt.getToken().getSubject())))
                        .anyRequest().denyAll());

        if (configured()) {
            var challenge = new BearerTokenAuthenticationEntryPoint();
            challenge.setResourceMetadataParameterResolver(request -> publicOrigin + METADATA);
            http.exceptionHandling(errors -> errors.authenticationEntryPoint(challenge))
                    .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(challenge)
                            .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                    resource -> resource.resource(publicUrl).authorizationServer(issuer)
                                            .scope("offline_access").tlsClientCertificateBoundAccessTokens(false)))
                            .jwt(jwt -> jwt.decoder(jwtDecoder(oauthHttp))))
                    .addFilterBefore(originFilter(), BearerTokenAuthenticationFilter.class);
            if (courses || !routing.isBlank()) {
                http.addFilterAfter(new CourseBodyLimit(MCP_REQUEST), AuthorizationFilter.class);
            }
        }
        return http.build();
    }

    private boolean configured() {
        return !publicUrl.isBlank() && !issuer.isBlank() && !ownerSubject.isBlank();
    }

    private JwtDecoder jwtDecoder(RestTemplate oauthHttp) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).restOperations(oauthHttp).build();
            var tokenType = new JwtTypeValidator("JWT", "at+jwt");
            tokenType.setAllowEmpty(true);
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                    new JwtIssuerValidator(issuer), tokenType,
                    new JwtClaimValidator<List<String>>("aud", audience -> audience != null
                            && audience.contains(publicUrl)),
                    new JwtClaimValidator<Object>("exp", expiration -> expiration != null)));
            return decoder;
        });
    }

    private Filter originFilter() {
        return (request, response, chain) -> {
            var httpRequest = (HttpServletRequest) request;
            String origin = httpRequest.getHeader("Origin");
            if (MCP_REQUEST.matches(httpRequest) && origin != null
                    && !publicOrigin.equals(origin) && !"https://chatgpt.com".equals(origin)) {
                ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            chain.doFilter(request, response);
        };
    }

    private static URI httpsUrl(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("OAuth issuer and MCP public URL must be absolute HTTPS URLs");
        }
        return uri;
    }
}
