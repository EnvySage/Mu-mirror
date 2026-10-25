package org.xianshen.mumirrorb.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.xianshen.mumirrorb.agent.AgentCommandException;
import org.xianshen.mumirrorb.agent.AgentWriteGrantService;
import org.xianshen.mumirrorb.agent.VerifiedGrant;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Authenticates only the opaque command grant; it never accepts user JWT as command authority. */
@Component
@RequiredArgsConstructor
public class AgentCommandAuthFilter extends OncePerRequestFilter {

    public static final String GRANT_HEADER = "X-Mirror-Command-Grant";
    private static final String PATH_PREFIX = "/internal/agent/commands/";

    private final AgentWriteGrantService grantService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (StringUtils.hasText(request.getHeader("Authorization"))) {
            writeUnauthorized(response);
            return;
        }
        VerifiedGrant grant;
        try {
            grant = grantService.verify(request.getHeader(GRANT_HEADER));
        } catch (AgentCommandException error) {
            writeUnauthorized(response);
            return;
        }
        try {
            var authentication = new UsernamePasswordAuthenticationToken(
                    grant, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT_COMMAND")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("code", "INVALID_COMMAND_GRANT"));
    }
}
