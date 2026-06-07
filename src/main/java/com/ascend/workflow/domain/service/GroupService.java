package com.ascend.workflow.domain.service;

import com.ascend.workflow.domain.model.GroupMember;
import com.ascend.workflow.domain.model.UserGroup;
import com.ascend.workflow.infrastructure.repository.GroupMemberRepository;
import com.ascend.workflow.infrastructure.repository.UserGroupRepository;
import com.ascend.workflow.infrastructure.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final UserGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;

    public Flux<UserGroup> findAll() {
        return groupRepository.findAll();
    }

    public Mono<UserGroup> findById(UUID id) {
        return groupRepository.findById(id)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Group not found: " + id)));
    }

    public Mono<UserGroup> createGroup(String name, String description) {
        UserGroup group = UserGroup.builder()
                .name(name)
                .description(description)
                .createdAt(OffsetDateTime.now())
                .build();
        return groupRepository.save(group);
    }

    public Mono<GroupMember> addMember(UUID groupId, UUID userId) {
        return groupRepository.existsById(groupId)
                .flatMap(exists -> exists
                        ? userRepository.existsById(userId)
                        : Mono.error(new ResourceNotFoundException("Group not found: " + groupId)))
                .flatMap(exists -> exists
                        ? memberRepository.existsByGroupIdAndUserId(groupId, userId)
                        : Mono.error(new ResourceNotFoundException("User not found: " + userId)))
                .flatMap(alreadyMember -> alreadyMember
                        ? Mono.error(new IllegalArgumentException("User is already a member of this group"))
                        : memberRepository.save(GroupMember.builder().groupId(groupId).userId(userId).build()));
    }
}
