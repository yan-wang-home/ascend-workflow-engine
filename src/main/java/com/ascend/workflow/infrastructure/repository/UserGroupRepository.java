package com.ascend.workflow.infrastructure.repository;

import com.ascend.workflow.domain.model.UserGroup;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface UserGroupRepository extends ReactiveCrudRepository<UserGroup, UUID> {
}
