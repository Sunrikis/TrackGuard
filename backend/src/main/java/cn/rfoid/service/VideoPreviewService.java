package cn.rfoid.service;

import cn.rfoid.api.ApiException;
import cn.rfoid.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VideoPreviewService {
    private record Preview(String owner, Path source, Instant created) {}
    private final Map<String, Preview> previews = new HashMap<>();
    private final AppProperties config;
    private final ObjectMapper mapper;
    private final Path root;
    private final Semaphore decoders = new Semaphore(2);
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "video-preview-cleanup"); thread.setDaemon(true); return thread;
    });
    public VideoPreviewService(AppProperties config, ObjectMapper mapper) throws IOException {
        this.config = config;
        this.mapper = mapper;
        root = Path.of(config.storage()).resolve("previews");
        Files.createDirectories(root);
        cleaner.scheduleWithFixedDelay(() -> {
            synchronized (this) {
                try {
                    expire();
                    // Remove expired files left by an interrupted previous process.
                    try (var files = Files.list(root)) {
                        for (Path path : files.toList()) {
                            if (path.getFileName().toString().matches("(video|frame)-.*\\.(mp4|mov|m4v|json)")
                                && Files.getLastModifiedTime(path).toInstant().isBefore(Instant.now().minusSeconds(1800)))
                                Files.deleteIfExists(path);
                        }
                    }
                } catch (IOException error) {
                    org.slf4j.LoggerFactory.getLogger(VideoPreviewService.class).warn("Preview cleanup failed", error);
                }
            }
        }, 60, 60, TimeUnit.SECONDS);
    }
    public synchronized Map<String, Object> upload(String owner, MultipartFile file) throws Exception {
        expire();
        if (previews.size() >= 16) throw new ApiException(429, "PREVIEW_BUSY", "预览任务较多，请稍后重试");
        String name = StorageService.cleanName(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!name.matches(".*\\.(mp4|mov|m4v)$") || file.isEmpty() || file.getSize() > 100L * 1024 * 1024)
            throw ApiException.invalid("请选择不超过 100 MB 的 MP4、MOV 或 M4V 视频");
        try (var stream = file.getInputStream()) {
            byte[] header = stream.readNBytes(12);
            if (header.length < 12 || !new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp"))
                throw ApiException.invalid("视频内容无效或文件已损坏");
        }
        Path source = Files.createTempFile(root, "video-", name.substring(name.lastIndexOf('.')));
        String id = UUID.randomUUID().toString();
        try {
            try (var stream = file.getInputStream()) { Files.copy(stream, source, StandardCopyOption.REPLACE_EXISTING); }
            Map<String, Object> result = extract(source, 0);
            previews.put(id, new Preview(owner, source, Instant.now()));
            result.put("id", id);
            return result;
        } catch (Exception error) { Files.deleteIfExists(source); throw error; }
    }
    public synchronized Map<String, Object> frame(String owner, String id, double seconds) throws Exception {
        expire();
        Preview preview = previews.get(id);
        if (preview == null || !preview.owner().equals(owner))
            throw new ApiException(404, "PREVIEW_EXPIRED", "预览已过期，请重新选择视频");
        return extract(preview.source(), seconds);
    }
    public synchronized void remove(String owner, String id) throws IOException {
        Preview preview = previews.get(id);
        if (preview != null && preview.owner().equals(owner)) {
            Files.deleteIfExists(preview.source());
            previews.remove(id);
        }
    }
    private void expire() throws IOException {
        var iterator = previews.values().iterator();
        while (iterator.hasNext()) {
            Preview preview = iterator.next();
            if (preview.created().isBefore(Instant.now().minusSeconds(1800))) {
                Files.deleteIfExists(preview.source()); iterator.remove();
            }
        }
    }
    public Map<String, Object> extract(Path source, double seconds) throws Exception {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > config.maxVideoSeconds())
            throw ApiException.invalid("视频时间点无效");
        if (!decoders.tryAcquire()) throw new ApiException(429, "PREVIEW_BUSY", "正在读取其他视频画面，请稍后重试");
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile(root, "frame-", ".json");
            Path script = Path.of(config.worker()).getParent().resolve("preview.py");
            process = new ProcessBuilder(config.python(), script.toString(), "--source", source.toString(),
                "--output", output.toString(), "--time", Double.toString(seconds),
                "--max-seconds", Integer.toString(config.maxVideoSeconds()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS))
                throw new ApiException(504, "PREVIEW_TIMEOUT", "视频画面读取超时，请重试或更换文件");
            if (process.exitValue() != 0)
                throw ApiException.invalid("无法读取视频画面，请检查文件是否完整且不超过 " + config.maxVideoSeconds() + " 秒");
            return mapper.readValue(output.toFile(), new TypeReference<Map<String, Object>>() {});
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            try { if (output != null) Files.deleteIfExists(output); }
            finally { decoders.release(); }
        }
    }
    @PreDestroy public synchronized void close() throws IOException {
        cleaner.shutdownNow();
        for (Preview preview : previews.values()) Files.deleteIfExists(preview.source());
        previews.clear();
    }
}
