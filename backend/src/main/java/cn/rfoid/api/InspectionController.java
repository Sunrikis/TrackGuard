package cn.rfoid.api;
import cn.rfoid.config.AppProperties;
import cn.rfoid.repository.InspectionRepository;
import cn.rfoid.service.TaskService;
import cn.rfoid.service.StorageService;
import cn.rfoid.service.ModelCatalog;

import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class InspectionController {
    private final InspectionRepository repo;
    private final TaskService tasks;
    private final StorageService storage;
    private final AppProperties config;
    private final ModelCatalog models;
    public InspectionController(InspectionRepository repo, TaskService tasks, StorageService storage, AppProperties config, ModelCatalog models) {
        this.repo=repo;this.tasks=tasks;this.storage=storage;this.config=config;this.models=models;
    }
    @GetMapping("/models") List<Map<String,Object>> models() { return models.list(); }
    @GetMapping("/health") Map<String,Object> health() {
        repo.healthy();
        boolean model=Files.isRegularFile(Path.of(config.model())) && Files.isRegularFile(Path.of(config.worker()));
        return Map.of("status","UP","database","SQL Server","modelReady",model,"maxFileMb",100,"maxVideoSeconds",config.maxVideoSeconds(),"inferenceWorker",tasks.workerStatus());
    }
    @GetMapping("/tasks") Map<String,Object> list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="12") int size,
        @RequestParam(defaultValue="createdAt") String sort,@RequestParam(defaultValue="desc") String direction) {
        if(q.length()>100||page<1||page>100000||size<1||size>100)throw ApiException.invalid("分页或搜索参数无效");
        return repo.list(q,status,page,size,sort,direction);
    }
    @GetMapping("/events/page") Map<String,Object> eventPage(@RequestParam(defaultValue="") String category,
        @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="createdAt") String sort,
        @RequestParam(defaultValue="desc") String direction) {
        if (page<1 || page>100000 || size<1 || size>100) throw ApiException.invalid("分页参数无效");
        return repo.eventPage(category,status,page,size,sort,direction);
    }
    @GetMapping("/tasks/{id}") Map<String,Object> get(@PathVariable String id) {
        Map<String,Object> task=repo.get(id);task.remove("inputFile");task.put("logs",repo.logs(id));return task;
    }
    @PostMapping(value="/tasks",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> create(@RequestPart MultipartFile file,@RequestParam(defaultValue="") String title,
        @RequestParam(defaultValue="0.5") double confidence,@RequestParam(defaultValue="1") double sampleSeconds,
        @RequestParam(defaultValue="[]") String roi, @RequestParam(defaultValue="") String modelId,
        @RequestParam(defaultValue="[\"person\",\"vehicle\",\"motorcycle\",\"animal\",\"obstacle\"]") String targetCategories) throws Exception {
        Map<String,Object> task=tasks.create(file,title,confidence,sampleSeconds,roi,modelId,targetCategories);task.remove("inputFile");
        return ResponseEntity.accepted().body(task);
    }
    @PostMapping("/tasks/{id}/cancel") Map<String,Object> cancel(@PathVariable String id) { tasks.cancel(id);return get(id); }
    @PostMapping("/tasks/{id}/retry") Map<String,Object> retry(@PathVariable String id) { tasks.retry(id);return get(id); }
    @DeleteMapping("/tasks/{id}") Map<String,String> deleteTask(@PathVariable String id) {
        tasks.delete(id); return Map.of("message", "任务及关联预警、日志已删除");
    }
    public record BatchDelete(List<String> ids) {}
    static List<String> deletionIds(BatchDelete input) {
        if (input == null || input.ids() == null || input.ids().isEmpty() || input.ids().size() > 500)
            throw ApiException.invalid("请选择 1 至 500 条记录");
        if (input.ids().stream().anyMatch(id -> id == null || !id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")))
            throw ApiException.invalid("记录编号无效");
        return input.ids().stream().distinct().sorted().toList();
    }
    @PostMapping("/tasks/batch-delete") Map<String,Object> deleteTasks(@RequestBody BatchDelete input) {
        List<String> ids = deletionIds(input);
        tasks.deleteBatch(ids);
        return Map.of("deletedCount", ids.size());
    }
    @PostMapping("/events/batch-delete") Map<String,Object> deleteEvents(@RequestBody BatchDelete input) {
        List<String> ids = deletionIds(input);
        repo.deleteEvents(ids);
        return Map.of("deletedCount", ids.size());
    }
    @DeleteMapping("/events/{id}") Map<String,String> deleteEvent(@PathVariable String id) {
        repo.deleteEvent(id); return Map.of("message", "预警记录已删除");
    }
    @GetMapping("/events") List<Map<String,Object>> events(@RequestParam(defaultValue="") String taskId,
        @RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String status) { return repo.events(taskId,category,status); }
    public record Review(String status,String note) {}
    @PatchMapping("/events/{id}") Map<String,String> review(@PathVariable String id,@RequestBody Review input) {
        if (input.status()==null || !Set.of("PENDING","PROCESSING","RESOLVED","FALSE_POSITIVE").contains(input.status())) throw ApiException.invalid("处理状态无效");
        String note=input.note()==null?"":input.note().strip();
        if(note.length()>1000)throw ApiException.invalid("处理备注不能超过 1000 字");
        if(Set.of("RESOLVED","FALSE_POSITIVE").contains(input.status()) && note.isBlank()) throw ApiException.invalid("请填写处理结果或误报原因");
        repo.review(id,input.status(),note);return Map.of("message","处理结果已保存");
    }
    @GetMapping("/statistics") Map<String,Object> statistics(@RequestParam(defaultValue="7") int days) {
        if(!Set.of(7,30,90).contains(days))throw ApiException.invalid("统计范围必须为 7、30 或 90 天");return repo.stats(days);
    }
    @GetMapping("/tasks/{id}/source") ResponseEntity<Resource> source(@PathVariable String id) {
        Map<String,Object> task=repo.get(id);Path file=storage.resolve((String)task.get("inputFile"));
        return serve(file,StorageService.isImage(file.toString())?(file.toString().endsWith(".png")?"image/png":"image/jpeg"):"video/mp4");
    }
    @SuppressWarnings("unchecked")
    @GetMapping("/tasks/{id}/assets/{name}") ResponseEntity<Resource> asset(@PathVariable String id,@PathVariable String name) {
        Map<String,Object> task=repo.get(id);
        if (!(task.get("result") instanceof Map<?,?> result)) throw ApiException.missing();
        if(!name.matches("frame-[0-9]{6}\\.jpg"))throw ApiException.missing();
        return serve(storage.resolve("tasks/"+id+"/"+result.get("run")+"/"+name),"image/jpeg");
    }
    private ResponseEntity<Resource> serve(Path path,String type) {
        if(!Files.isRegularFile(path))throw ApiException.missing();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("X-Content-Type-Options","nosniff")
            .cacheControl(CacheControl.noCache()).body(new FileSystemResource(path));
    }
}
