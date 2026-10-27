package com.empmg.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final com.empmg.platform.user.repo.UserRepo users;
    private final com.empmg.platform.auth.service.SensitiveRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path=request.getRequestURI();
        if(java.util.Set.of("/api/auth/login","/api/auth/forgot-password","/api/auth/reset-password","/api/auth/onboarding").contains(path)
            &&!rateLimiter.allow("public:"+path+":"+request.getRemoteAddr(),(path.endsWith("onboarding")||path.endsWith("login"))?100:20)) {
            limited(response);return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthPrincipal principal = jwtService.parseAccess(header.substring(7));
            if (principal != null) {
                var user=users.findById(principal.userId()).orElse(null);
                if(user==null || !"ACTIVE".equals(user.getStatus()) || !user.getCompanyId().equals(principal.companyId())) {
                    chain.doFilter(request,response); return;
                }
                boolean welcomePath=path.startsWith("/api/auth/welcome/");
                if(user.isWelcomeRequired() && !welcomePath && !java.util.Set.of("/api/auth/me","/api/auth/logout").contains(path)) {
                    response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Complete your account setup before using the portal.\"}"); return;
                }
                if(user.isPasswordChangeRequired() && !welcomePath && !java.util.Set.of("/api/auth/me","/api/auth/me/password","/api/auth/logout").contains(request.getRequestURI())) {
                    response.setStatus(403); response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Change your temporary password before using the portal.\"}"); return;
                }
                principal=new AuthPrincipal(user.getId(),user.getCompanyId(),user.getEmail(),user.getAccessRole(),user.getEmployeeId());
                if(!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())
                    &&!rateLimiter.allow("write:"+user.getId(),200)) { limited(response);return; }
                var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.role().authorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
    private void limited(HttpServletResponse response) throws IOException {
        response.setStatus(429); response.setHeader("Retry-After","900");response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Too many requests. Please wait a few minutes and try again.\"}");
    }
}
