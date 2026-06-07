package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.GroupMember;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface GroupMemberRepository extends ReactiveCrudRepository<GroupMember, UUID> {

    @Query("SELECT EXISTS(SELECT 1 FROM group_members WHERE group_id = :groupId AND user_id = :userId)")
    Mono<Boolean> existsByGroupIdAndUserId(UUID groupId, UUID userId);

    @Query("SELECT COUNT(*) FROM group_members WHERE group_id = :groupId")
    Mono<Long> countByGroupId(UUID groupId);
}
