package za.co.vlugboek.security;

import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import za.co.vlugboek.repo.AppUserRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserRepository users) throws Exception {
        TokenAuthenticationFilter tokenAuthenticationFilter = new TokenAuthenticationFilter(users);

        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(jsonUnauthorized())
                        .accessDeniedHandler(jsonForbidden()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/healthz", "/api/healthz", "/api/auth/**", "/h2-console/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/federations", "/api/clubs", "/api/lofts").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/federations").hasAnyRole("SYSTEM_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/federations/*/admin").hasAnyRole("SYSTEM_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/federations/*").hasAnyRole("SYSTEM_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/federations/*").hasAnyRole("SYSTEM_ADMIN", "ADMIN")
                        .requestMatchers("/api/admin/ingestion-runs", "/api/admin/ingestion-runs/**").hasAnyRole("SYSTEM_ADMIN", "ADMIN")
                        .requestMatchers("/api/admin/**").hasAnyRole("SYSTEM_ADMIN", "FEDERATION_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/documents").hasAnyRole("SYSTEM_ADMIN", "FEDERATION_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/documents/upload").hasAnyRole("SYSTEM_ADMIN", "FEDERATION_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/documents/*/confirm").hasAnyRole("SYSTEM_ADMIN", "FEDERATION_ADMIN", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    private AuthenticationEntryPoint jsonUnauthorized() {
        return (request, response, authException) -> writeJsonError(response, 401, "Sign in to continue");
    }

    private AccessDeniedHandler jsonForbidden() {
        return (request, response, accessDeniedException) -> writeJsonError(response, 403, "You do not have permission to do that");
    }

    private void writeJsonError(jakarta.servlet.http.HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
