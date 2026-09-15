package cn.rfoid.api;

import cn.rfoid.config.SessionSecurity;
import cn.rfoid.repository.AccountRepository;
import cn.rfoid.repository.AccountRepository.Account;
import cn.rfoid.service.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountRepository accounts;
    private final AvatarService avatars;
    private final StorageService storage;
    private final String dummyHash = PasswordHasher.hash(UUID.randomUUID().toString());
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private record Window(long until, AtomicInteger count) {}
    public record Credentials(String username, String password, String displayName) {}
    public record Profile(String id, String username, String displayName, String avatarUrl) {}
    public record SessionView(Profile user, String csrfToken) {}

    public AuthController(AccountRepository accounts, AvatarService avatars, StorageService storage) {
        this.accounts = accounts; this.avatars = avatars; this.storage = storage;
    }
    private Profile profile(Account a) {
        return new Profile(a.id(), a.username(), a.displayName(), a.avatarFile() == null ? null : "/api/auth/avatar?v=" + a.avatarFile().substring(8));
    }
    @GetMapping("/session") public SessionView session(HttpServletRequest request) {
        HttpSession session = request.getSession();
        String id = (String) session.getAttribute(SessionSecurity.USER);
        return new SessionView(id == null ? null : profile(accounts.get(id)), SessionSecurity.token(session));
    }
    private void limit(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> e.getValue().until < now);
        Window window = attempts.computeIfAbsent(request.getRemoteAddr(), key -> new Window(now + 60_000, new AtomicInteger()));
        if (window.count.incrementAndGet() > 20) throw new ApiException(429, "LOGIN_RATE_LIMIT", "尝试次数较多，请一分钟后再试");
    }
    private String username(Credentials input) {
        if (input.username() == null || !input.username().strip().matches("[A-Za-z0-9_]{3,32}"))
            throw ApiException.invalid("用户名需为 3–32 位字母、数字或下划线");
        if (input.password() == null || input.password().length() < 8 || input.password().length() > 128)
            throw ApiException.invalid("密码长度需为 8–128 位");
        return input.username().strip().toLowerCase(Locale.ROOT);
    }
    @PostMapping("/register") public ResponseEntity<SessionView> register(@RequestBody Credentials input, HttpServletRequest request) {
        limit(request);
        String username = username(input);
        String display = input.displayName() == null || input.displayName().isBlank() ? username : input.displayName().strip();
        if (display.length() > 40 || display.matches(".*[\\p{Cntrl}].*")) throw ApiException.invalid("昵称不能超过 40 字或包含控制字符");
        Account account = accounts.create(username, display, PasswordHasher.hash(input.password()));
        SessionSecurity.login(request, account.id());
        return ResponseEntity.status(201).body(session(request));
    }
    @PostMapping("/login") public SessionView login(@RequestBody Credentials input, HttpServletRequest request) {
        limit(request);
        Account account = accounts.byUsername(username(input));
        boolean valid = PasswordHasher.matches(input.password(), account == null ? dummyHash : account.passwordHash());
        if (account == null || !valid) throw new ApiException(401, "BAD_CREDENTIALS", "用户名或密码不正确");
        SessionSecurity.login(request, account.id());
        return session(request);
    }
    @PostMapping("/logout") public Map<String, String> logout(HttpServletRequest request) {
        request.getSession().invalidate();
        return Map.of("message", "已退出登录");
    }
    @PostMapping(value="/avatar", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Profile uploadAvatar(@RequestPart MultipartFile file, HttpServletRequest request) throws Exception {
        String id = SessionSecurity.userId(request);
        String filename = avatars.save(file);
        accounts.avatar(id, filename);
        return profile(accounts.get(id));
    }
    @GetMapping("/avatar") public ResponseEntity<Resource> avatar(HttpServletRequest request) {
        String filename = accounts.get(SessionSecurity.userId(request)).avatarFile();
        if (filename == null) throw ApiException.missing();
        var path = storage.resolve(filename);
        if (!java.nio.file.Files.isRegularFile(path)) throw ApiException.missing();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(new FileSystemResource(path));
    }
}
