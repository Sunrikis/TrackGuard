package cn.rfoid.api;

import cn.rfoid.config.*;
import cn.rfoid.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class AccountAndModelTests {
    @TempDir Path temp;
    AppProperties config() { return new AppProperties(temp.toString(),"python","worker.py",temp.resolve("trackguard-world.pt").toString(),30,120,8,180); }

    @Test void passwordsUseUniqueSaltAndRejectWrongPassword() {
        String first = PasswordHasher.hash("secret-中文-123");
        String second = PasswordHasher.hash("secret-中文-123");
        assertNotEquals(first, second);
        assertTrue(PasswordHasher.matches("secret-中文-123", first));
        assertFalse(PasswordHasher.matches("incorrect-123", first));
        assertFalse(PasswordHasher.matches("secret-中文-123", "invalid"));
    }
    @Test void unauthenticatedRequestsCannotReadRecordsOrAssets() {
        for (String path : List.of("/api/tasks", "/api/tasks/id/assets/frame-000000.jpg", "/api/models", "/api/auth/avatar")) {
            var request = new MockHttpServletRequest("GET", path);
            ApiException error = assertThrows(ApiException.class, () -> new SessionSecurity.ApiGuard().preHandle(request, new MockHttpServletResponse(), new Object()));
            assertEquals(401, error.status);
        }
    }
    @Test void registrationRequiresCsrfEvenBeforeLogin() {
        var request = new MockHttpServletRequest("POST", "/api/auth/register");
        assertEquals(403, assertThrows(ApiException.class, () -> new SessionSecurity.ApiGuard().preHandle(request, new MockHttpServletResponse(), new Object())).status);
        request.addHeader("X-CSRF-Token", SessionSecurity.token(request.getSession()));
        assertTrue(new SessionSecurity.ApiGuard().preHandle(request, new MockHttpServletResponse(), new Object()));
    }
    @Test void successfulLoginRotatesSessionAndCsrfToken() {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        String oldSession = request.getSession().getId(), oldToken = SessionSecurity.token(request.getSession());
        SessionSecurity.login(request, "user-id");
        assertNotEquals(oldSession, request.getSession().getId());
        assertNotEquals(oldToken, SessionSecurity.token(request.getSession()));
        assertEquals("user-id", SessionSecurity.userId(request));
    }
    @Test void selectedTargetsMustBeNonemptyKnownAndUnique() {
        for (List<String> invalid : List.of(List.<String>of(), List.of("person", "person"), List.of("anything")))
            assertThrows(ApiException.class, () -> DetectionValidator.categories(invalid));
        assertThrows(ApiException.class, () -> DetectionValidator.categories(Arrays.asList("person", null)));
        assertEquals(List.of("person", "animal"), DetectionValidator.categories(List.of("person", "animal")));
        var all = List.of("person", "vehicle", "motorcycle", "animal", "obstacle");
        assertEquals(all, DetectionValidator.categories(all));
    }
    @Test void modelCatalogRejectsUnknownOrTraversalSelection() throws Exception {
        Files.write(temp.resolve("trackguard-world.pt"), new byte[2048]);
        ModelCatalog catalog = new ModelCatalog(config(), new ObjectMapper());
        assertEquals("trackguard-world.pt", catalog.require("").id());
        assertThrows(ApiException.class, () -> catalog.require("../private.pt"));
        assertThrows(ApiException.class, () -> catalog.require("missing.pt"));
    }
    @Test void modelCatalogStopsListingDeletedWeights() throws Exception {
        Files.write(temp.resolve("trackguard-world.pt"), new byte[2048]);
        Files.write(temp.resolve("secondary.pt"), new byte[2048]);
        var categories = List.of("person", "vehicle", "motorcycle", "animal", "obstacle");
        new ObjectMapper().writeValue(temp.resolve("catalog.json").toFile(), List.of(
            Map.of("id", "trackguard-world.pt", "name", "TrackGuard", "description", "Default", "categories", categories),
            Map.of("id", "secondary.pt", "name", "Secondary", "description", "Optional", "categories", categories)
        ));
        ModelCatalog catalog = new ModelCatalog(config(), new ObjectMapper());
        assertEquals(2, catalog.list().size());

        Files.delete(temp.resolve("secondary.pt"));

        assertEquals(List.of("trackguard-world.pt"), catalog.list().stream().map(item -> (String)item.get("id")).toList());
    }
    @Test void avatarIsCroppedAndReencodedToPng() throws Exception {
        var source = new BufferedImage(600, 320, BufferedImage.TYPE_INT_RGB);
        var buffer = new ByteArrayOutputStream(); ImageIO.write(source, "jpg", buffer);
        var storage = new StorageService(config());
        String filename = new AvatarService(storage).save(new MockMultipartFile("file", "avatar.jpg", "image/jpeg", buffer.toByteArray()));
        var output = ImageIO.read(storage.resolve(filename).toFile());
        assertEquals(256, output.getWidth()); assertEquals(256, output.getHeight());
        assertTrue(filename.matches("avatars/[a-f0-9-]+\\.png"));
    }
    @Test void avatarRejectsFakeOrOversizedImages() throws Exception {
        var avatars = new AvatarService(new StorageService(config()));
        assertThrows(ApiException.class, () -> avatars.save(new MockMultipartFile("file", "a.png", "image/png", "not image".getBytes())));
        assertThrows(ApiException.class, () -> avatars.save(new MockMultipartFile("file", "a.png", "image/png", new byte[2 * 1024 * 1024 + 1])));
    }
}
