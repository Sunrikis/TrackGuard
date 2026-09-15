package cn.rfoid.service;

import cn.rfoid.api.ApiException;
import cn.rfoid.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class VideoPreviewTests {
    @TempDir Path temp;
    VideoPreviewService service(boolean stub) throws Exception {
        var config = new AppProperties(temp.toString(), "python", "ai-core/worker.py", "model.pt", 30, 120, 8, 180);
        if (!stub) return new VideoPreviewService(config, new ObjectMapper());
        return new VideoPreviewService(config, new ObjectMapper()) {
            @Override public Map<String, Object> extract(Path source, double seconds) {
                return new HashMap<>(Map.of("image", "jpeg", "time", seconds, "duration", 4));
            }
        };
    }
    @Test void previewBelongsToUploaderAndRemovalDeletesSource() throws Exception {
        var service = service(true);
        try {
            byte[] header = new byte[]{0,0,0,12,102,116,121,112,0,0,0,0};
            var result = service.upload("owner", new MockMultipartFile("file", "video.mp4", "video/mp4", header));
            String id = (String) result.get("id");
            assertThrows(ApiException.class, () -> service.frame("other", id, 0));
            service.remove("other", id);
            assertEquals(2.0, service.frame("owner", id, 2).get("time"));
            service.remove("owner", id);
            assertThrows(ApiException.class, () -> service.frame("owner", id, 0));
            try (var files = Files.list(temp.resolve("previews"))) { assertEquals(0, files.count()); }
        } finally { service.close(); }
    }
    @Test void invalidFilesAndTimestampsNeverStartDecoder() throws Exception {
        var service = service(false);
        try {
            var file = new MockMultipartFile("file", "video.mp4", "video/mp4", "not a video".getBytes());
            assertThrows(ApiException.class, () -> service.upload("owner", file));
            for (double seconds : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 121})
                assertThrows(ApiException.class, () -> service.extract(temp, seconds));
            try (var files = Files.list(temp.resolve("previews"))) { assertEquals(0, files.count()); }
        } finally { service.close(); }
    }
}
