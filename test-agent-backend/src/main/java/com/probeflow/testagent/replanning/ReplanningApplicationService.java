package com.probeflow.testagent.replanning;

import com.probeflow.testagent.task.TaskRepository;
import com.probeflow.testagent.task.TaskStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplanningApplicationService {

    private final TaskRepository tasks;

    public ReplanningApplicationService(TaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public ReplanningResult replan(ReplanningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Replanning request is required");
        }
        var task = tasks.findById(request.taskId());
        if (task.isEmpty()) {
            return ReplanningResult.failed(request.trigger(), List.of("Task not found: " + request.taskId()));
        }
        if (task.get().getStatus() == TaskStatus.COMPLETED) {
            return ReplanningResult.notTriggerable(request.trigger(), List.of("Completed task cannot be replanned"));
        }
        if (task.get().getStatus() == TaskStatus.CANCELLED) {
            return ReplanningResult.notTriggerable(request.trigger(), List.of("Cancelled task cannot be replanned"));
        }
        return ReplanningResult.noop(
            request.trigger(),
            "Minimal replanning entrypoint accepted the trigger and left the plan unchanged."
        );
    }
}
