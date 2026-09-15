package cn.rfoid.service;
import cn.rfoid.config.AppProperties;
import cn.rfoid.api.ApiException;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {
    private final Path root;
    public StorageService(AppProperties config) throws IOException {
        root = Path.of(config.storage()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }
    public Path root() { return root; }
    public Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw ApiException.invalid("文件路径无效");
        return path;
    }
    public String store(String id, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 100L * 1024 * 1024) throw ApiException.invalid("请上传非空且不超过 100 MB 的文件");
        String name = cleanName(file.getOriginalFilename());
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        if (!Set.of(".jpg", ".jpeg", ".png", ".mp4", ".mov", ".m4v").contains(ext))
            throw ApiException.invalid("仅支持 JPG、PNG 图片或 MP4、MOV、M4V 短视频");
        try (InputStream stream = file.getInputStream()) {
            byte[] header = stream.readNBytes(16);
            boolean valid = switch (ext) {
                case ".jpg", ".jpeg" -> header.length >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216 && (header[2] & 255) == 255;
                case ".png" -> header.length >= 8 && Arrays.equals(Arrays.copyOf(header, 8), new byte[]{(byte)137,80,78,71,13,10,26,10});
                default -> header.length >= 12 && new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp");
            };
            if (!valid) throw ApiException.invalid("文件内容与扩展名不匹配，或文件已损坏");
        }
        String relative = "tasks/" + id + "/input" + ext;
        Path path = resolve(relative);
        Files.createDirectories(path.getParent());
        try (InputStream stream = file.getInputStream()) { Files.copy(stream, path); }
        return relative;
    }
    public static String cleanName(String name) {
        if (name == null) return "未命名文件";
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        return clean.length() > 250 ? clean.substring(clean.length() - 250) : clean;
    }
    public static boolean isImage(String name) { return name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png)$"); }
}
