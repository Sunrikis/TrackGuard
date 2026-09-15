package cn.rfoid.repository;
import cn.rfoid.api.ApiException;
import cn.rfoid.service.StorageService;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InspectionRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public InspectionRepository(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    public boolean healthy() { return Integer.valueOf(1).equals(db.queryForObject("SELECT 1",Integer.class)); }
    String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    Object decode(String value) {
        if (value == null) return null;
        try { return json.readValue(value, Object.class); } catch (Exception e) { throw new IllegalStateException("Invalid saved JSON", e); }
    }
    @Transactional
    public void create(String id, String title, String filename, String input, double confidence, double sample, Object roi,
                       String modelId, String modelName, List<String> categories) {
        db.update("INSERT INTO inspection_task (id,title,source_name,media_type,input_file,confidence,sample_seconds,roi_json,model_id,model_name,target_categories) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            id, title, filename, StorageService.isImage(filename) ? "IMAGE" : "VIDEO", input, confidence, sample, encode(roi), modelId, modelName, encode(categories));
        log(id, "INFO", "文件已保存，任务进入检测队列");
    }
    public void log(String id, String level, String message) {
        db.update("INSERT INTO task_log (task_id,level,message) VALUES (?,?,?)", id, level, message.substring(0, Math.min(1000, message.length())));
    }
    static String time(ResultSet rs, String key) throws SQLException {
        LocalDateTime value = rs.getObject(key, LocalDateTime.class);
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toString();
    }
    private Map<String,Object> task(ResultSet rs, boolean detail) throws SQLException {
        Map<String,Object> m = new LinkedHashMap<>();
        for (String key : List.of("id", "title", "status")) m.put(key, rs.getString(key));
        m.put("sourceName", rs.getString("source_name")); m.put("mediaType", rs.getString("media_type"));
        m.put("progress", rs.getInt("progress")); m.put("confidence", rs.getDouble("confidence"));
        m.put("sampleSeconds", rs.getDouble("sample_seconds")); m.put("attempt", rs.getInt("attempt"));
        m.put("createdAt", time(rs,"created_at")); m.put("startedAt", time(rs,"started_at")); m.put("finishedAt", time(rs,"finished_at"));
        m.put("elapsedMs", rs.getObject("elapsed_ms")); m.put("errorMessage", rs.getString("error_message"));
        m.put("eventCount", rs.getInt("event_count"));
        m.put("modelId", rs.getString("model_id")); m.put("modelName", rs.getString("model_name"));
        m.put("targetCategories", decode(rs.getString("target_categories")));
        if (detail) {
            m.put("roi", decode(rs.getString("roi_json"))); m.put("result", decode(rs.getString("result_json")));
            m.put("inputFile", rs.getString("input_file"));
        }
        return m;
    }
    private static final String TASK_SELECT = "SELECT t.*, (SELECT COUNT(*) FROM warning_event e WHERE e.task_id=t.id) event_count FROM inspection_task t";
    public Map<String,Object> get(String id) {
        List<Map<String,Object>> rows = db.query(TASK_SELECT + " WHERE t.id=?", (rs,n)->task(rs,true),id);
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.getFirst();
    }
    public Map<String,Object> list(String q, String status, int page, int size) {
        return list(q, status, page, size, "createdAt", "desc");
    }
    public Map<String,Object> list(String q, String status, int page, int size, String sort, String direction) {
        String column = switch (sort) {
            case "title" -> "t.title";
            case "mediaType" -> "t.media_type";
            case "modelName" -> "t.model_name";
            case "status" -> "t.status";
            case "eventCount" -> "event_count";
            case "createdAt" -> "t.created_at";
            default -> throw ApiException.invalid("排序字段无效");
        };
        String order = sortDirection(direction);
        String where = " WHERE (t.title LIKE ? OR t.source_name LIKE ?)";
        List<Object> args = new ArrayList<>(List.of("%"+q+"%", "%"+q+"%"));
        if (!status.isBlank()) { where += " AND t.status=?"; args.add(status); }
        long total = db.queryForObject("SELECT COUNT_BIG(*) FROM inspection_task t"+where, Long.class,args.toArray());
        args.add((page-1)*size); args.add(size);
        List<Map<String,Object>> items = db.query(TASK_SELECT+where+" ORDER BY "+column+" "+order+",t.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            (rs,n)->task(rs,false), args.toArray());
        return Map.of("items",items,"total",total,"page",page,"size",size);
    }
    private static String sortDirection(String direction) {
        if (!Set.of("asc", "desc").contains(direction)) throw ApiException.invalid("排序方向无效");
        return direction.toUpperCase(Locale.ROOT);
    }
    public Map<String,Object> eventPage(String category, String status, int page, int size, String sort, String direction) {
        String column = switch (sort) {
            case "label" -> "e.label";
            case "taskTitle" -> "t.title";
            case "risk" -> "CASE WHEN e.risk='HIGH' THEN 2 ELSE 1 END";
            case "status" -> "e.status";
            case "confidence" -> "e.confidence";
            case "createdAt" -> "e.created_at";
            default -> throw ApiException.invalid("排序字段无效");
        };
        String order = sortDirection(direction);
        String from = " FROM warning_event e JOIN inspection_task t ON t.id=e.task_id WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (!category.isBlank()) { from += " AND e.category=?"; args.add(category); }
        if (!status.isBlank()) { from += " AND e.status=?"; args.add(status); }
        long total = db.queryForObject("SELECT COUNT_BIG(*)"+from, Long.class, args.toArray());
        args.add((page-1)*size); args.add(size);
        var rows = db.query("SELECT e.*,t.title task_title,t.model_name"+from+" ORDER BY "+column+" "+order+",e.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", (rs,n)->event(rs), args.toArray());
        return Map.of("items", rows, "total", total, "page", page, "size", size);
    }
    public List<Map<String,Object>> logs(String id) {
        return db.query("SELECT TOP (100) level,message,created_at FROM task_log WHERE task_id=? ORDER BY id DESC", (rs,n)->
            Map.of("level",rs.getString("level"),"message",rs.getString("message"),"createdAt",time(rs,"created_at")), id);
    }
    public boolean start(String id) {
        return db.update("UPDATE inspection_task SET status='RUNNING',started_at=SYSUTCDATETIME(),progress=1 WHERE id=? AND status='QUEUED'",id)==1;
    }
    public void progress(String id, int value) {
        db.update("UPDATE inspection_task SET progress=? WHERE id=? AND status='RUNNING'",Math.clamp(value,1,99),id);
    }
    @Transactional
    public boolean cancel(String id) {
        boolean changed=db.update("UPDATE inspection_task SET status='CANCELLED',finished_at=SYSUTCDATETIME() WHERE id=? AND status IN ('QUEUED','RUNNING')",id)==1;
        if (changed) log(id,"INFO","用户取消了检测任务");
        return changed;
    }
    @Transactional
    public boolean retry(String id) {
        boolean changed=db.update("UPDATE inspection_task SET status='QUEUED',progress=0,error_message=NULL,result_json=NULL,started_at=NULL,finished_at=NULL,elapsed_ms=NULL,attempt=attempt+1 WHERE id=? AND status IN ('FAILED','CANCELLED')",id)==1;
        if (changed) log(id,"INFO","重新执行检测，保留原始输入与参数");
        return changed;
    }
    @Transactional
    public void fail(String id, String message) {
        if (db.update("UPDATE inspection_task SET status='FAILED',error_message=?,finished_at=SYSUTCDATETIME() WHERE id=? AND status IN ('QUEUED','RUNNING')",message,id)==1)
            log(id,"ERROR",message);
    }
    @Transactional
    public void complete(String id, Map<String,Object> result, long elapsed, List<Map<String,Object>> detections) {
        if (db.update("UPDATE inspection_task SET status='SUCCEEDED',progress=100,result_json=?,elapsed_ms=?,finished_at=SYSUTCDATETIME() WHERE id=? AND status='RUNNING'",encode(result),elapsed,id)!=1) return;
        for (Map<String,Object> d : detections) {
            if (!Boolean.TRUE.equals(d.get("inDanger"))) continue;
            db.update("INSERT INTO warning_event (id,task_id,track_key,category,label,confidence,risk,frame_time,snapshot,box_json,advice,advice_source) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,d.get("trackKey"),d.get("category"),d.get("label"),d.get("confidence"),d.get("risk"),d.get("frameTime"),d.get("snapshot"),encode(d.get("box")),d.get("advice"),d.getOrDefault("adviceSource","LOCAL_RULE"));
        }
        log(id,"INFO","检测完成，结果、截图与预警已保存");
    }
    public void recover() {
        for (String id : db.queryForList("SELECT id FROM inspection_task WHERE status IN ('QUEUED','RUNNING')", String.class)) fail(id,"服务重启导致任务中断，请重新执行");
    }
    private Map<String,Object> event(ResultSet rs) throws SQLException {
        Map<String,Object> m=new LinkedHashMap<>();
        for (String key:List.of("id","category","label","risk","status","advice")) m.put(key,rs.getString(key));
        m.put("adviceSource",rs.getString("advice_source"));
        m.put("taskId",rs.getString("task_id")); m.put("taskTitle",rs.getString("task_title"));
        m.put("modelName",rs.getString("model_name"));
        m.put("confidence",rs.getDouble("confidence")); m.put("frameTime",rs.getDouble("frame_time"));
        m.put("snapshot",rs.getString("snapshot")); m.put("box",decode(rs.getString("box_json")));
        m.put("reviewNote",rs.getString("review_note")); m.put("reviewedAt",time(rs,"reviewed_at"));m.put("createdAt",time(rs,"created_at"));
        return m;
    }
    public List<Map<String,Object>> events(String taskId, String category, String status) {
        String sql="SELECT TOP (500) e.*,t.title task_title,t.model_name FROM warning_event e JOIN inspection_task t ON t.id=e.task_id WHERE 1=1";
        List<Object> args=new ArrayList<>();
        if (!taskId.isBlank()) { sql+=" AND e.task_id=?";args.add(taskId); }
        if (!category.isBlank()) { sql+=" AND e.category=?";args.add(category); }
        if (!status.isBlank()) { sql+=" AND e.status=?";args.add(status); }
        return db.query(sql+" ORDER BY e.created_at DESC,e.id", (rs,n)->event(rs),args.toArray());
    }
    @Transactional
    public void review(String id, String status, String note) {
        List<String> ids=db.queryForList("SELECT task_id FROM warning_event WHERE id=?",String.class,id);
        if (ids.isEmpty()) throw ApiException.missing();
        db.update("UPDATE warning_event SET status=?,review_note=?,reviewed_at=SYSUTCDATETIME() WHERE id=?",status,note,id);
        log(ids.getFirst(),"INFO","预警复核："+id+" → "+status+"；"+note);
    }
    public Map<String,Object> stats(int days) {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Timestamp since=Timestamp.valueOf(today.minusDays(days-1).atStartOfDay().minusHours(8));
        Map<String,Object> tasks=db.queryForMap("SELECT COUNT(*) totalTasks,COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END),0) completedTasks,COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) failedTasks,COALESCE(AVG(CAST(elapsed_ms AS FLOAT)),0) avgElapsedMs FROM inspection_task WHERE created_at>=?",since);
        Map<String,Object> warnings=db.queryForMap("SELECT COUNT(*) allEvents,COALESCE(SUM(CASE WHEN status<>'FALSE_POSITIVE' THEN 1 ELSE 0 END),0) warningCount,COALESCE(SUM(CASE WHEN status='RESOLVED' THEN 1 ELSE 0 END),0) resolvedCount,COALESCE(SUM(CASE WHEN status='FALSE_POSITIVE' THEN 1 ELSE 0 END),0) falsePositiveCount FROM warning_event WHERE created_at>=?",since);
        long pending=db.queryForObject("SELECT COUNT_BIG(*) FROM warning_event WHERE status IN ('PENDING','PROCESSING')",Long.class);
        List<Map<String,Object>> categories=db.queryForList("SELECT category,COUNT(*) count FROM warning_event WHERE created_at>=? AND status<>'FALSE_POSITIVE' GROUP BY category",since);
        List<Map<String,Object>> trend=db.queryForList("SELECT CONVERT(varchar(10),DATEADD(hour,8,created_at),23) date,COUNT(*) count FROM warning_event WHERE created_at>=? AND status<>'FALSE_POSITIVE' GROUP BY CONVERT(varchar(10),DATEADD(hour,8,created_at),23) ORDER BY date",since);
        Map<String,Object> result=new LinkedHashMap<>(tasks);result.putAll(warnings);
        result.put("pendingCount",pending);result.put("categories",categories);result.put("trend",trend);result.put("days",days);result.put("today",today.toString());
        return result;
    }
    @Transactional
    public void deleteTask(String id) {
        List<String> states = db.queryForList("SELECT status FROM inspection_task WITH (UPDLOCK,HOLDLOCK) WHERE id=?", String.class, id);
        if (states.isEmpty()) throw ApiException.missing();
        if (Set.of("QUEUED", "RUNNING").contains(states.getFirst()))
            throw new ApiException(409, "TASK_BUSY", "请先取消任务，等待检测停止后再删除");
        db.update("DELETE FROM warning_event WHERE task_id=?", id);
        db.update("DELETE FROM task_log WHERE task_id=?", id);
        db.update("DELETE FROM inspection_task WHERE id=?", id);
    }
    @Transactional
    public void deleteTasks(List<String> ids) {
        for (String id : ids) deleteTask(id);
    }
    @Transactional
    public void deleteEvents(List<String> ids) {
        for (String id : ids) deleteEvent(id);
    }
    @Transactional
    public void deleteEvent(String id) {
        List<String> ids = db.queryForList("SELECT task_id FROM warning_event WHERE id=?", String.class, id);
        if (ids.isEmpty()) throw ApiException.missing();
        String taskId = ids.getFirst();
        // Lock the parent first, matching task deletion and task completion order.
        List<String> states = db.queryForList("SELECT status FROM inspection_task WITH (UPDLOCK,HOLDLOCK) WHERE id=?", String.class, taskId);
        if (states.isEmpty()) throw ApiException.missing();
        if (Set.of("QUEUED", "RUNNING").contains(states.getFirst()))
            throw new ApiException(409, "TASK_BUSY", "任务尚未结束，请稍后删除预警");
        if (db.update("DELETE FROM warning_event WHERE id=? AND task_id=?", id, taskId) != 1)
            throw ApiException.missing();
        log(taskId, "INFO", "删除预警记录：" + id);
    }
}
