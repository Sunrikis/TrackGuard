package cn.rfoid.config;

import cn.rfoid.api.ApiException;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class SessionSecurity implements WebMvcConfigurer {
    public static final String USER = "trackguard.user";
    private static final String CSRF = "trackguard.csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String token(HttpSession session) {
        synchronized (session) {
            if (session.getAttribute(CSRF) == null) {
                byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
                session.setAttribute(CSRF, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            }
            return (String) session.getAttribute(CSRF);
        }
    }
    public static String userId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(USER);
        if (id == null) throw new ApiException(401, "LOGIN_REQUIRED", "请先登录后再使用巡检工作空间");
        return id;
    }
    public static void login(HttpServletRequest request, String id) {
        request.getSession();
        request.changeSessionId();
        request.getSession().setAttribute(USER, id);
        request.getSession().removeAttribute(CSRF);
    }

    public static class ApiGuard implements HandlerInterceptor {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                String path = request.getRequestURI().substring(request.getContextPath().length());
                String method = request.getMethod();
                boolean publicGet = method.equals("GET") && Set.of("/api/health", "/api/auth/session").contains(path);
                boolean publicPost = method.equals("POST") && Set.of("/api/auth/login", "/api/auth/register").contains(path);
                if (!publicGet && !publicPost) userId(request);
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
                    HttpSession session = request.getSession(false);
                    String expected = session == null ? null : (String) session.getAttribute(CSRF);
                    String received = request.getHeader("X-CSRF-Token");
                    if (expected == null || received == null || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8)))
                        throw new ApiException(403, "CSRF_INVALID", "页面会话已更新，请刷新页面后重试");
                }
                return true;
            }
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiGuard()).addPathPatterns("/api/**");
    }
}
