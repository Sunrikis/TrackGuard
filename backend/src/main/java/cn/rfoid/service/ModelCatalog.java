package cn.rfoid.service;

import cn.rfoid.api.ApiException;
import cn.rfoid.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Only administrator-registered local weights can be selected by an API caller. */
@Service
public class ModelCatalog {
    public record Entry(String id, String name, String description, List<String> categories) {}
    private final List<Entry> entries;
    private final Path directory;
    private final String defaultId;
    public ModelCatalog(AppProperties config, ObjectMapper json) throws Exception {
        Path configured = Path.of(config.model()).toAbsolutePath().normalize();
        directory = configured.getParent();
        defaultId = configured.getFileName().toString();
        Path catalog = directory.resolve("catalog.json");
        List<Entry> values = Files.isRegularFile(catalog)
            ? json.readValue(catalog.toFile(), new TypeReference<List<Entry>>() {}) : new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Entry entry : values) {
            if (entry.id() == null || !entry.id().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.pt") || !ids.add(entry.id())
                || entry.name() == null || entry.name().isBlank() || entry.name().length() > 100)
                throw new IllegalStateException("Invalid model catalog entry");
            DetectionValidator.categories(entry.categories());
        }
        if (!ids.contains(defaultId)) values.add(new Entry(defaultId, defaultId, "管理员配置的本地检测模型", List.of("person", "vehicle", "motorcycle", "animal", "obstacle")));
        entries = List.copyOf(values);
    }
    public Entry require(String id) {
        String selected = id == null || id.isBlank() ? defaultId : id;
        Entry entry = entries.stream().filter(e -> e.id().equals(selected)).findFirst().orElseThrow(() -> ApiException.invalid("所选模型不存在，请刷新模型列表"));
        if (!available(entry)) throw new ApiException(409, "MODEL_NOT_READY", "所选模型文件未就绪，请检查本地权重");
        return entry;
    }
    public Path path(Entry entry) { return directory.resolve(entry.id()); }
    private boolean available(Entry entry) {
        try { return Files.isRegularFile(path(entry)) && Files.size(path(entry)) >= 1024; }
        catch (Exception e) { return false; }
    }
    public List<Map<String,Object>> list() {
        return entries.stream().filter(this::available).map(e -> {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("id", e.id()); item.put("name", e.name()); item.put("description", e.description());
            item.put("categories", e.categories()); item.put("available", true); item.put("isDefault", e.id().equals(defaultId));
            return item;
        }).toList();
    }
}
