package cn.rfoid.service;
import cn.rfoid.config.AppProperties;
import cn.rfoid.api.ApiException;
import cn.rfoid.repository.InspectionRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TaskService {
    private final InspectionRepository repo;
    private final StorageService storage;
    private final AppProperties config;
    private final ObjectMapper json;
    private final ModelCatalog models;
    private final InferenceWorker worker;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    public TaskService(InspectionRepository repo, StorageService storage, AppProperties config, ObjectMapper json, ModelCatalog models, InferenceWorker worker) {
        this.repo=repo;this.storage=storage;this.config=config;this.json=json;this.models=models;this.worker=worker;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(config.queueCapacity()),
            r->{Thread t=new Thread(r,"trackguard-inference");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        repo.recover();
    }
    public Map<String,Object> workerStatus() { return worker.status(); }
    public Map<String,Object> create(MultipartFile file, String title, double confidence, double sample, String roiJson, String modelId, String categoriesJson) throws IOException {
        DetectionValidator.number(confidence,0.1,0.95); DetectionValidator.sampleSeconds(sample);
        ModelCatalog.Entry model = models.require(modelId);
        List<String> categories;
        try { categories = DetectionValidator.categories(json.readValue(categoriesJson, new TypeReference<List<String>>() {})); }
        catch (Exception e) { throw ApiException.invalid("请至少选择一种有效的识别目标"); }
        if (!model.categories().containsAll(categories)) throw ApiException.invalid("所选模型不支持部分识别目标，请调整选择");
        List<List<Double>> roi;
        try { roi=json.readValue(roiJson,new TypeReference<>(){});DetectionValidator.roi(roi); }
        catch (Exception e) { throw ApiException.invalid("危险区无效，请沿边界按顺序标定 4 至 12 个点，或使用自动识别"); }
        String filename=StorageService.cleanName(file.getOriginalFilename());
        String taskTitle=title.isBlank()?filename:title.strip();
        if (taskTitle.length()>100) throw ApiException.invalid("任务名称不能超过 100 字");
        if (executor.getQueue().remainingCapacity()==0) throw new ApiException(429,"QUEUE_FULL","检测队列已满，请稍后提交");
        String id=UUID.randomUUID().toString();
        String input=storage.store(id,file);
        repo.create(id,taskTitle,filename,input,confidence,sample,roi,model.id(),model.name(),categories);
        submit(id);
        return repo.get(id);
    }
    private void submit(String id) {
        Job job=new Job(id);
        if (jobs.putIfAbsent(id,job)!=null) throw new ApiException(409,"TASK_BUSY","任务正在停止，请稍后重试");
        try { executor.execute(job); }
        catch (RejectedExecutionException e) {
            jobs.remove(id,job);repo.fail(id,"检测队列已满，请重新执行");
            throw new ApiException(429,"QUEUE_FULL","检测队列已满，任务已保留，可稍后重新执行");
        }
    }
    public synchronized Map<String,Object> retry(String id) {
        repo.get(id);
        if (jobs.containsKey(id)) throw new ApiException(409,"TASK_BUSY","任务正在停止，请稍后重试");
        if (!repo.retry(id)) throw new ApiException(409,"INVALID_STATE","只有失败或已取消的任务可以重新执行");
        submit(id);return repo.get(id);
    }
    public Map<String,Object> cancel(String id) {
        repo.get(id);
        if (!repo.cancel(id)) throw new ApiException(409,"INVALID_STATE","此任务已结束，无法取消");
        Job job=jobs.get(id);
        if (job!=null) {
            job.cancelled=true;
            if (executor.remove(job)) jobs.remove(id,job);
        }
        return repo.get(id);
    }
    public synchronized void delete(String id) {
        if (jobs.containsKey(id)) throw new ApiException(409, "TASK_BUSY", "任务尚未停止，请先取消并稍后重试删除");
        repo.deleteTask(id);
    }
    public synchronized void deleteBatch(List<String> ids) {
        if (ids.stream().anyMatch(jobs::containsKey))
            throw new ApiException(409, "TASK_BUSY", "所选任务中有尚未停止的任务，本次未删除任何记录");
        repo.deleteTasks(ids);
    }
    @PreDestroy public void stop() { jobs.values().forEach(j->j.cancelled=true);executor.shutdownNow();worker.close(); }
    private final class Job implements Runnable {
        private final String id;
        private volatile boolean cancelled;
        Job(String id) { this.id=id; }
        @Override public void run() {
            try { execute(); }
            catch (CancellationException ignored) { /* Cancellation was already persisted by the API. */ }
            catch (InferenceWorker.TaskTimeoutException e) { if(!cancelled)repo.fail(id,"检测超过 "+config.inferenceTimeoutSeconds()+" 秒，已终止。请缩短视频或增大抽帧间隔后重试"); }
            catch (InferenceWorker.StartupException e) {
                if(!cancelled)repo.fail(id,"检测工作进程初始化失败或超过 "+config.workerStartupTimeoutSeconds()+" 秒，请检查 Python 与模型环境后重试；详情见工作进程日志");
                org.slf4j.LoggerFactory.getLogger(TaskService.class).error("Worker startup failed for task {}",id,e);
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt();if(!cancelled)repo.fail(id,"检测任务被中断，请重新执行"); }
            catch (Exception e) {
                if (!cancelled) {
                    org.slf4j.LoggerFactory.getLogger(TaskService.class).error("Inference task {} failed",id,e);
                    repo.fail(id,"模型处理失败，请检查模型与 Python 环境后重新执行；详细原因已记录在服务日志");
                }
            } finally { jobs.remove(id,this); }
        }
        @SuppressWarnings("unchecked")
        private void execute() throws Exception {
            if (cancelled || !repo.start(id)) return;
            Map<String,Object> task=repo.get(id);
            Path runDir=storage.resolve("tasks/"+id+"/run-"+task.get("attempt"));
            Files.createDirectories(runDir);
            Map<String,Object> request=new LinkedHashMap<>();
            request.put("source",storage.resolve((String)task.get("inputFile")).toString());
            ModelCatalog.Entry selectedModel = models.require((String)task.get("modelId"));
            request.put("model",models.path(selectedModel).toString());
            request.put("targetCategories",task.get("targetCategories"));
            request.put("confidence",task.get("confidence"));request.put("sampleSeconds",task.get("sampleSeconds"));
            request.put("roi",task.get("roi"));request.put("maxVideoSeconds",config.maxVideoSeconds());
            Path requestFile=runDir.resolve("request.json");json.writeValue(requestFile.toFile(),request);
            repo.log(id,"INFO","开始检测；模型："+task.get("modelName")+"；识别目标："+task.get("targetCategories"));
            long started=System.nanoTime();
            if (cancelled) return;
            InferenceWorker.RunMetrics metrics=worker.execute(id+"-"+task.get("attempt"),requestFile,runDir,()->cancelled,value->repo.progress(id,value));
            if (cancelled) return;
            Path resultFile=runDir.resolve("result.json");
            if (!Files.isRegularFile(resultFile) || Files.size(resultFile)>4*1024*1024) throw new IOException("Missing or oversized model output; see "+runDir.resolve("worker.log"));
            Map<String,Object> result=json.readValue(resultFile.toFile(),new TypeReference<>(){});
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                String code=Objects.toString(result.get("code"),"");
                String message=switch(code) {
                    case "RAIL_NOT_FOUND" -> "无法可靠识别轨道区域。请新建任务，在预览图上手动标定危险区后重试";
                    case "VIDEO_TOO_LONG" -> "视频超过 120 秒，请截取短视频后重新上传";
                    case "TOO_MANY_FRAMES" -> "单任务最多检测 14400 帧，请截短视频或改用间隔抽帧";
                    case "INVALID_MEDIA" -> "无法解码此文件，请使用有效的 JPG / PNG 图片或 H.264 MP4 视频";
                    case "MODEL_NOT_READY" -> "检测模型未就绪，请先运行模型准备脚本或配置有效权重";
                    default -> "模型运行失败，请检查服务日志后重试";
                };
                repo.fail(id,message);return;
            }
            List<Map<String,Object>> detections=DetectionValidator.result(result,runDir);
            if (detections.stream().anyMatch(d -> !((List<?>)task.get("targetCategories")).contains(d.get("category"))))
                throw new IOException("Model returned a category outside the requested selection");
            boolean video="VIDEO".equals(task.get("mediaType"));
            if (result.get("visionAdvice") instanceof Map<?,?> advice && Boolean.TRUE.equals(advice.get("configured")))
                repo.log(id,"INFO",(video?"整段视频":"图片")+"图像理解：候选证据 "
                    +Objects.toString(advice.get("requestedSnapshots"),"0")+" 张，采用关键帧 "
                    +Objects.toString(advice.get("generatedSnapshots"),"0")+" 张，失败 "
                    +Objects.toString(advice.get("failedSnapshots"),"0")+" 张，跳过 "
                    +Objects.toString(advice.get("skippedSnapshots"),"0")+" 张"
                    +(Objects.toString(advice.get("errorMessage"),"").isBlank()?"":"；原因："
                        +Objects.toString(advice.get("errorMessage"),"")));
            long elapsed = Duration.ofNanos(System.nanoTime()-started).toMillis();
            if (result.get("performance") instanceof Map<?,?> rawPerformance) {
                Map<String,Object> performance=(Map<String,Object>)rawPerformance;
                double frames=((Number)result.get("sampledFrames")).doubleValue();
                performance.put("taskFps",Math.round(frames*1000/Math.max(elapsed,1)*100)/100.0);
            }
            if (result.get("timings") instanceof Map<?,?> rawTiming) {
                Map<String,Object> timing = (Map<String,Object>)rawTiming;
                double workerMs = DetectionValidator.number(timing.get("workerMs"),0,3_600_000);
                timing.put("workerWaitMs",Math.round(metrics.workerWaitMs()*100)/100.0);
                timing.put("processOverheadMs",Math.max(0,elapsed-workerMs-metrics.workerWaitMs()));
                timing.put("totalMs",elapsed);
            }
            result.put("run", "run-"+task.get("attempt"));
            repo.complete(id,result,elapsed,detections);
        }
    }
}
