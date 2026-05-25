package za.co.vlugboek.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.repo.AppUserRepository;

public class TokenAuthenticationFilter extends OncePerRequestFilter {
    private final AppUserRepository users;

    public TokenAuthenticationFilter(AppUserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveToken(request);
            if (token != null) {
                users.findBySessionToken(token).ifPresent(user -> authenticate(request, user));
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return cleanToken(authorization.substring(7));
        }
        return cleanToken(request.getHeader("X-Vlugboek-Token"));
    }

    private String cleanToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.trim();
    }

    private void authenticate(HttpServletRequest request, AppUser user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().responseName()));
        if (user.getRole().isSystemAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
        }
        if (user.getRole().isFederationAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_FEDERATION_ADMIN"));
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
