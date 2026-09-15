package cn.rfoid.api;

import cn.rfoid.repository.InspectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Optional SQL Server check: all fixtures are created and rolled back in one transaction. */
@EnabledIfEnvironmentVariable(named="RFOID_SQL_TEST", matches="true")
class DeletionSqlTests {
    @Test void deletionMaintainsForeignKeysCountsAndUnrelatedRows() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.exists(root.resolve(".env"))) root = root.getParent();
        Map<String,String> env = new HashMap<>();
        for (String line : Files.readAllLines(root.resolve(".env"))) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.strip().startsWith("#")) env.put(line.substring(0, equals).strip(), line.substring(equals+1).strip());
        }
        env.putAll(System.getenv());
        var source = new DriverManagerDataSource(env.get("DB_URL"), env.get("DB_USERNAME"), env.get("DB_PASSWORD"));
        var db = new JdbcTemplate(source);
        var repo = new InspectionRepository(db, new ObjectMapper());
        String schema = Files.readString(root.resolve("backend/src/main/resources/schema.sql"));
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            for (int pass = 0; pass < 2; pass++)
                for (String statement : schema.split("(?m)^GO\\s*$"))
                    if (!statement.isBlank()) db.execute(statement);
            String task = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
            for (String id : List.of(task, other)) repo.create(id, "验证-删除事务", "test.jpg", "test-only.jpg", .35, 1,
                List.of(), "test.pt", "test", List.of("person"));
            assertEquals(409, assertThrows(ApiException.class, () -> repo.deleteTask(task)).status);
            assertNotNull(repo.get(task));
            assertTrue(repo.start(task));
            assertEquals(409, assertThrows(ApiException.class, () -> repo.deleteTask(task)).status);
            Map<String,Object> detection = new HashMap<>(Map.of("inDanger", true, "trackKey", "one", "category", "person",
                "label", "人员", "confidence", .9, "risk", "HIGH", "frameTime", 0, "snapshot", "frame-000000.jpg",
                "box", List.of(.1,.1,.2,.2), "advice", "测试"));
            repo.complete(task, Map.of("warningCount", 2), 10, List.of(detection, new HashMap<>(detection) {{ put("trackKey", "two"); }}));
            String motorcycle = UUID.randomUUID().toString();
            repo.create(motorcycle, "验证-摩托车分类", "test.jpg", "test-only.jpg", .35, 1,
                List.of(), "test.pt", "test", List.of("motorcycle"));
            assertTrue(repo.start(motorcycle));
            var motorcycleDetection = new HashMap<>(detection);
            motorcycleDetection.put("category", "motorcycle");
            motorcycleDetection.put("label", "摩托车");
            repo.complete(motorcycle, Map.of("warningCount", 1), 10, List.of(motorcycleDetection));
            assertEquals("motorcycle", repo.events(motorcycle, "motorcycle", "").getFirst().get("category"));
            repo.deleteTask(motorcycle);
            var events = repo.events(task, "", "");
            assertEquals(2, events.size());
            var pageOne = repo.eventPage("person", "", 1, 1, "confidence", "asc");
            var pageTwo = repo.eventPage("person", "", 2, 1, "confidence", "asc");
            var first = (Map<?,?>)((List<?>)pageOne.get("items")).getFirst();
            var second = (Map<?,?>)((List<?>)pageTwo.get("items")).getFirst();
            assertNotEquals(first.get("id"), second.get("id"));
            assertTrue(((Number)first.get("confidence")).doubleValue() <= ((Number)second.get("confidence")).doubleValue());
            for (String field : List.of("label", "taskTitle", "risk", "status", "createdAt", "confidence")) {
                assertNotNull(repo.eventPage("", "", 1, 20, field, "asc"));
                assertNotNull(repo.eventPage("", "", 1, 20, field, "desc"));
            }
            for (String field : List.of("title", "mediaType", "modelName", "status", "eventCount", "createdAt"))
                assertNotNull(repo.list("", "", 1, 12, field, "desc"));
            assertThrows(ApiException.class, () -> repo.eventPage("", "", 1, 20, "id;DROP TABLE warning_event", "asc"));
            assertThrows(ApiException.class, () -> repo.list("", "", 1, 12, "title", "invalid"));
            // SQL Server supports named savepoints but its JDBC driver cannot release them.
            db.execute("SAVE TRANSACTION batch_task_test");
            assertThrows(ApiException.class, () -> repo.deleteTasks(List.of(task, other)));
            db.execute("ROLLBACK TRANSACTION batch_task_test");
            assertNotNull(repo.get(task));
            assertEquals(2, repo.events(task, "", "").size());
            String missing = UUID.randomUUID().toString();
            db.execute("SAVE TRANSACTION batch_event_test");
            assertThrows(ApiException.class, () -> repo.deleteEvents(List.of((String)events.getFirst().get("id"), missing)));
            db.execute("ROLLBACK TRANSACTION batch_event_test");
            assertEquals(2, repo.events(task, "", "").size());
            String event = (String) events.getFirst().get("id");
            repo.deleteEvent(event);
            assertEquals(1, repo.events(task, "", "").size());
            assertEquals(1, repo.get(task).get("eventCount"));
            assertEquals(404, assertThrows(ApiException.class, () -> repo.deleteEvent(event)).status);
            repo.deleteEvents(repo.events(task, "", "").stream().map(row -> (String)row.get("id")).toList());
            assertTrue(repo.events(task, "", "").isEmpty());
            repo.deleteTask(task);
            assertEquals(404, assertThrows(ApiException.class, () -> repo.get(task)).status);
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM task_log WHERE task_id=?", Integer.class, task));
            assertTrue(repo.events(task, "", "").isEmpty());
            assertNotNull(repo.get(other));
            assertEquals(404, assertThrows(ApiException.class, () -> repo.deleteTask(task)).status);
            repo.cancel(other);
            repo.deleteTask(other);
            assertEquals(404, assertThrows(ApiException.class, () -> repo.get(other)).status);
            var batch = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            for (String id : batch) {
                repo.create(id, "验证-批量删除事务", "test.jpg", "test-only.jpg", .35, 1, List.of(), "test.pt", "test", List.of("person"));
                repo.cancel(id);
            }
            repo.deleteTasks(batch);
            for (String id : batch) assertEquals(404, assertThrows(ApiException.class, () -> repo.get(id)).status);
        });
    }
}
