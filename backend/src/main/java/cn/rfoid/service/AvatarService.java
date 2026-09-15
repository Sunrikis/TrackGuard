package cn.rfoid.service;

import cn.rfoid.api.ApiException;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AvatarService {
    private final StorageService storage;
    public AvatarService(StorageService storage) { this.storage = storage; }
    public String save(MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024) throw ApiException.invalid("头像请选择不超过 2 MB 的 JPG 或 PNG 图片");
        BufferedImage source;
        try (ImageInputStream input = ImageIO.createImageInputStream(file.getInputStream())) {
            if (input == null) throw ApiException.invalid("头像图片无法读取");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw ApiException.invalid("请上传有效的 JPG 或 PNG 头像");
            ImageReader reader = readers.next();
            try {
                if (!Set.of("JPEG", "PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT)))
                    throw ApiException.invalid("仅支持 JPG 或 PNG 头像");
                reader.setInput(input, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || (long)w * h > 16_000_000) throw ApiException.invalid("头像分辨率不能超过 1600 万像素");
                source = reader.read(0);
            } finally { reader.dispose(); }
        } catch (java.io.IOException e) { throw ApiException.invalid("头像文件已损坏，请重新选择"); }
        BufferedImage target = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int side = Math.min(source.getWidth(), source.getHeight());
            int x = (source.getWidth() - side) / 2, y = (source.getHeight() - side) / 2;
            graphics.drawImage(source, 0, 0, 256, 256, x, y, x + side, y + side, null);
        } finally { graphics.dispose(); }
        String filename = "avatars/" + UUID.randomUUID() + ".png";
        Path path = storage.resolve(filename);
        Files.createDirectories(path.getParent());
        ImageIO.write(target, "png", path.toFile());
        return filename;
    }
}
