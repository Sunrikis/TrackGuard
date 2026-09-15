package cn.rfoid.api;

import cn.rfoid.config.SessionSecurity;
import cn.rfoid.repository.InspectionRepository;
import cn.rfoid.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeletionTests {
    @Test void deleteRequiresLoginAndCsrfAndDispatchesOnlyOnConfirmationRequest() throws Exception {
        var repo = mock(InspectionRepository.class);
        var tasks = mock(TaskService.class);
        var api = new InspectionController(repo, tasks, null, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiErrors())
            .addInterceptors(new SessionSecurity.ApiGuard()).build();
        mvc.perform(delete("/api/tasks/task-id")).andExpect(status().isUnauthorized());
        var session = new MockHttpSession();
        session.setAttribute(SessionSecurity.USER, "test-user");
        String token = SessionSecurity.token(session);
        mvc.perform(delete("/api/events/event-id").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(repo, tasks);
        mvc.perform(delete("/api/tasks/task-id").session(session).header("X-CSRF-Token", token)).andExpect(status().isOk());
        verify(tasks).delete("task-id");
        mvc.perform(delete("/api/events/event-id").session(session).header("X-CSRF-Token", token)).andExpect(status().isOk());
        verify(repo).deleteEvent("event-id");
        String id = "00000000-0000-0000-0000-000000000001";
        String body = "{\"ids\":[\""+id+"\",\""+id+"\"]}";
        mvc.perform(post("/api/tasks/batch-delete").session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks/batch-delete").session(session).header("X-CSRF-Token", token).contentType("application/json").content(body)).andExpect(status().isOk());
        verify(tasks).deleteBatch(java.util.List.of(id));
        mvc.perform(post("/api/events/batch-delete").session(session).header("X-CSRF-Token", token).contentType("application/json").content(body)).andExpect(status().isOk());
        verify(repo).deleteEvents(java.util.List.of(id));
        for (String invalid : java.util.List.of("{}", "{\"ids\":[]}", "{\"ids\":[null]}", "{\"ids\":[\"invalid\"]}"))
            mvc.perform(post("/api/tasks/batch-delete").session(session).header("X-CSRF-Token", token).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events/page?size=500").session(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/events/page?size=20&page=2&sort=risk&direction=asc").session(session)).andExpect(status().isOk());
        verify(repo).eventPage("", "", 2, 20, "risk", "asc");
        doThrow(new ApiException(409, "TASK_BUSY", "任务未停止")).when(tasks).delete("busy-id");
        mvc.perform(delete("/api/tasks/busy-id").session(session).header("X-CSRF-Token", token)).andExpect(status().isConflict());
    }
}
