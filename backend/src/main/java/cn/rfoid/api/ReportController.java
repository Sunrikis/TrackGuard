package cn.rfoid.api;
import cn.rfoid.repository.InspectionRepository;
import cn.rfoid.service.StorageService;
import cn.rfoid.service.DetectionValidator;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/{id}")
public class ReportController {
    private final InspectionRepository repo;
    private final StorageService storage;
    public ReportController(InspectionRepository repo,StorageService storage) { this.repo=repo;this.storage=storage; }
    static String escape(Object value) {
        return Objects.toString(value,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    static String status(Object value) {
        return switch(Objects.toString(value,"")) { case "PENDING"->"待处理";case "PROCESSING"->"处理中";case "RESOLVED"->"已处理";case "FALSE_POSITIVE"->"误报";default->"未知"; };
    }
    static String localTime(Object value) {
        if(value==null)return "—";
        return OffsetDateTime.parse(value.toString()).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    static String sampling(Object value) {
        return value instanceof Number n && n.doubleValue()==0 ? "逐帧检测" : "每 "+value+" 秒一帧";
    }
    static String regionSource(Object value) {
        return switch(Objects.toString(value,"")) { case "MANUAL"->"人工多点标定";case "AUTO_CURVE"->"轨道曲线几何估计";default->"轨道直线几何估计"; };
    }
    static String adviceSource(Object value) { return "DEEPSEEK".equals(value)?"DeepSeek 图像理解":"本地规则"; }
    @SuppressWarnings("unchecked")
    @GetMapping("/report") ResponseEntity<byte[]> report(@PathVariable String id) throws Exception {
        Map<String,Object> task=repo.get(id);
        if (!"SUCCEEDED".equals(task.get("status"))) throw new ApiException(409,"RESULT_NOT_READY","检测完成后才能导出报告");
        Map<String,Object> result=(Map<String,Object>)task.get("result");
        List<Map<String,Object>> events=repo.events(id,"","");
        StringBuilder html=new StringBuilder("""
            <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>轨安 · 巡检报告</title><style>
            *{box-sizing:border-box}body{margin:0;background:#eef1f6;color:#172338;font:14px/1.8 'Microsoft YaHei',sans-serif}.page{max-width:960px;margin:32px auto;background:white;padding:48px;border-radius:16px}h1{font-size:28px;margin:8px 0}h2{font-size:18px;border-left:4px solid #4c6fff;padding-left:12px;margin-top:32px}.brand{color:#4c6fff;font-weight:bold;letter-spacing:2px}.meta{display:grid;grid-template-columns:1fr 1fr;gap:8px;padding:20px;background:#f5f7fb;border-radius:12px}.muted{color:#6e7a8d}table{width:100%;border-collapse:collapse}td,th{padding:10px;border-bottom:1px solid #e4e9f1;text-align:left;font-size:12px}img{max-width:100%;max-height:550px;display:block;margin:18px auto;border-radius:8px}.event{break-inside:avoid;border:1px solid #e4e9f1;padding:22px;border-radius:12px;margin:18px 0}.event img{max-height:370px}.tag{color:#d14c52}button{background:#4c6fff;color:white;border:0;border-radius:8px;padding:10px 20px;cursor:pointer}.print{float:right}@media print{body{background:white}.page{margin:0;padding:16px;max-width:none}.print{display:none}h2{break-after:avoid}img{max-height:420px}}@page{size:A4;margin:14mm}
            </style></head><body><main class="page"><button class="print" onclick="window.print()">打印 / 保存为 PDF</button><div class="brand">TRACKGUARD / 轨安</div><h1>铁路轨道安全巡检报告</h1>
            """);
        html.append("<p class='muted'>").append(escape(task.get("title"))).append("</p><div class='meta'>");
        for(var field:List.of(new Object[]{"任务编号",id},new Object[]{"原始文件",task.get("sourceName")},new Object[]{"检测时间",localTime(task.get("createdAt"))},new Object[]{"生成时间",LocalDateTime.now(ZoneId.of("Asia/Shanghai"))},new Object[]{"置信度阈值",task.get("confidence")},new Object[]{"视频检测方式",sampling(task.get("sampleSeconds"))},new Object[]{"区域来源",regionSource(result.get("regionSource"))},new Object[]{"检测耗时",task.get("elapsedMs")+" ms"})) {
            html.append("<div><span class='muted'>").append(field[0]).append("：</span>").append(escape(field[1])).append("</div>");
        }
        html.append("<div><span class='muted'>检测模型：</span>").append(escape(task.get("modelName"))).append("</div><div><span class='muted'>识别目标：</span>").append(escape(task.get("targetCategories"))).append("</div>");
        html.append("</div><h2>巡检结论</h2><p>原始检测发现 ").append(result.get("objectCount")).append(" 个目标，当前保留预警记录 ").append(task.get("eventCount")).append(" 条，原始危险区外目标 ").append(result.get("outsideCount")).append(" 个。已删除的预警不计入当前记录数，原始检测截图保留。视频记录按照目标关联合并，人工标记误报的记录仍保留用于追溯。</p>");
        if (result.get("visionAdvice") instanceof Map<?,?> vision
                && !Objects.toString(vision.get("overallAdvice"),"").isBlank()) {
            html.append("<h2>").append("VIDEO".equals(vision.get("scope"))?"整段视频处置建议":"图像理解处置建议")
                .append("</h2><p>").append(escape(vision.get("overallAdvice"))).append("</p><p class='muted'>DeepSeek 已结合完整检测结果与 ")
                .append(escape(vision.get("generatedSnapshots"))).append(" 张代表性关键帧生成建议，请结合现场复核。</p>");
        }
        if(events.isEmpty()) html.append("<p>当前没有预警记录，记录可能已被删除。请结合原始检测画面复核。</p>");
        html.append("<h2>检测截图</h2>").append(image(id,result,Objects.toString(result.get("preview"))));
        html.append("<h2>预警明细与处置建议</h2>");
        for(Map<String,Object> e:events) {
            html.append("<section class='event'><strong class='tag'>").append(escape(e.get("label"))).append(" · ").append("HIGH".equals(e.get("risk"))?"高风险":"中风险").append("</strong><p>置信度 ").append(String.format(Locale.ROOT,"%.1f%%",((Number)e.get("confidence")).doubleValue()*100)).append(" · 视频位置 ").append(e.get("frameTime")).append(" 秒 · ").append(status(e.get("status"))).append("</p>");
            html.append(image(id,result,e.get("snapshot").toString())).append("<p><b>处置建议（").append(adviceSource(e.get("adviceSource"))).append("）：</b>").append(escape(e.get("advice"))).append("</p><p><b>处理备注：</b>").append(escape(e.get("reviewNote"))).append("</p><p class='muted'>复核时间：").append(localTime(e.get("reviewedAt"))).append("</p></section>");
        }
        html.append("<h2>模型与检测边界</h2><p>模型：").append(escape(result.get("model"))).append("；处理帧数：").append(result.get("sampledFrames")).append("；有效类别：").append(escape(result.get("capabilities"))).append("。</p><p class='muted'>自动轨道区域先进行直线估计，再对平滑弯道使用分段曲线回退；道岔、严重遮挡或移动机位需要人工多点标定。视频图像理解会发送完整结构化检测结果和少量变化关键帧，不会逐帧上传；调用失败则保留本地规则建议。抽帧和目标关联仍可能漏检或重复计数，所有结果均需由值守人员结合现场情况复核。</p><p class='muted' style='word-break:break-all'>模型 SHA-256：").append(escape(result.get("modelSha256"))).append("</p></main></body></html>");
        return download(html.toString().getBytes(StandardCharsets.UTF_8),"text/html;charset=UTF-8","inspection-"+id+".html");
    }
    private String image(String id,Map<String,Object> result,String name) throws Exception {
        Path run=storage.resolve("tasks/"+id+"/"+result.get("run"));DetectionValidator.checkAsset(name,run);
        return "<img alt='轨道区域与异物检测截图' src='data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(Files.readAllBytes(run.resolve(name)))+"'>";
    }
    static String csv(Object value) {
        String s=Objects.toString(value,"");
        if(s.matches("^[\\s]*[=+@-].*"))s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    @GetMapping("/events.csv") ResponseEntity<byte[]> csv(@PathVariable String id) {
        Map<String,Object> task=repo.get(id);
        StringBuilder text=new StringBuilder("\ufeff任务编号,检测模型,异物类别,置信度,风险等级,视频时间秒,检测时间,处理状态,处理备注,建议来源,处置建议\r\n");
        for(Map<String,Object> e:repo.events(id,"","")) {
            Object[] values={id,task.get("modelName"),e.get("label"),e.get("confidence"),e.get("risk"),e.get("frameTime"),localTime(e.get("createdAt")),status(e.get("status")),e.get("reviewNote"),adviceSource(e.get("adviceSource")),e.get("advice")};
            text.append(String.join(",",Arrays.stream(values).map(ReportController::csv).toList())).append("\r\n");
        }
        return download(text.toString().getBytes(StandardCharsets.UTF_8),"text/csv;charset=UTF-8","events-"+id+".csv");
    }
    private ResponseEntity<byte[]> download(byte[] bytes,String type,String filename) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("Content-Disposition","attachment; filename=\""+filename+"\"")
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(bytes);
    }
}
