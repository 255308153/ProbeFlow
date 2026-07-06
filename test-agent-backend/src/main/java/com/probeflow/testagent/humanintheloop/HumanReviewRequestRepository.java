package com.probeflow.testagent.humanintheloop;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanReviewRequestRepository extends JpaRepository<HumanReviewRequest, String> {

    List<HumanReviewRequest> findByTaskIdOrderByCreatedAtAsc(String taskId);

    List<HumanReviewRequest> findByStatusOrderByCreatedAtAsc(HumanRequestStatus status);

    List<HumanReviewRequest> findByRequestTypeOrderByCreatedAtAsc(HumanRequestType requestType);

    List<HumanReviewRequest> findByTaskIdAndStatusOrderByCreatedAtAsc(String taskId, HumanRequestStatus status);
}
