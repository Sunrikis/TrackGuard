package cn.rfoid.api;

import cn.rfoid.config.SessionSecurity;
import cn.rfoid.repository.InspectionRepository;
import cn.rfoid.service.StorageService;
import cn.rfoid.service.VideoPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class VideoPreviewController {
    private final VideoPreviewService previews;
    private final InspectionRepository repo;
    private final StorageService storage;
    public VideoPreviewController(VideoPreviewService previews, InspectionRepository repo, StorageService storage) {
        this.previews = previews; this.repo = repo; this.storage = storage;
    }
    @PostMapping("/video-previews")
    Map<String, Object> upload(@RequestPart MultipartFile file, HttpServletRequest request) throws Exception {
        return previews.upload(SessionSecurity.userId(request), file);
    }
    @GetMapping("/video-previews/{id}")
    Map<String, Object> frame(@PathVariable String id, @RequestParam(defaultValue="0") double time,
                              HttpServletRequest request) throws Exception {
        return previews.frame(SessionSecurity.userId(request), id, time);
    }
    @DeleteMapping("/video-previews/{id}")
    void remove(@PathVariable String id, HttpServletRequest request) throws Exception {
        previews.remove(SessionSecurity.userId(request), id);
    }
    @GetMapping("/tasks/{id}/source-frame")
    Map<String, Object> source(@PathVariable String id, @RequestParam(defaultValue="0") double time) throws Exception {
        Map<String, Object> task = repo.get(id);
        if (!"VIDEO".equals(task.get("mediaType"))) throw ApiException.invalid("此素材不是视频");
        return previews.extract(storage.resolve((String) task.get("inputFile")), time);
    }
}
