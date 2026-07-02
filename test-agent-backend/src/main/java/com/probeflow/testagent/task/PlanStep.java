package com.probeflow.testagent.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plan_step")
public class PlanStep {

    @Id
    @Column(name = "step_id", nullable = false, updatable = false, length = 36)
    private String stepId;

    @Column(name = "task_id", nullable = false, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 32)
    private PlanStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_status", nullable = false, length = 16)
    private PlanStepStatus stepStatus;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "goal", columnDefinition = "text")
    private String goal;

    @Column(name = "input_ref", columnDefinition = "text")
    private String inputRef;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    void onCreate() {
        if (stepId == null) {
            stepId = UUID.randomUUID().toString();
        }
    }

    public String getStepId() {
        return stepId;
    }

    public void setStepId(String stepId) {
        this.stepId = stepId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public PlanStepType getStepType() {
        return stepType;
    }

    public void setStepType(PlanStepType stepType) {
        this.stepType = stepType;
    }

    public PlanStepStatus getStepStatus() {
        return stepStatus;
    }

    public void setStepStatus(PlanStepStatus stepStatus) {
        this.stepStatus = stepStatus;
    }

    public Integer getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(Integer stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getInputRef() {
        return inputRef;
    }

    public void setInputRef(String inputRef) {
        this.inputRef = inputRef;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
