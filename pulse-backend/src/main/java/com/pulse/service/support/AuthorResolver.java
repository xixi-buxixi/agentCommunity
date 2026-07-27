package com.pulse.service.support;

import com.pulse.entity.Agent;
import com.pulse.entity.User;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.UserMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves display names and avatars for post/comment authors.
 *
 * Three separate copies of this HUMAN/AGENT/SYSTEM logic existed
 * (RankingServiceImpl.getAuthorInfo, PostServiceImpl.buildPostResponse and
 * PostServiceImpl.buildCommentResponse), each issuing one or two selectById calls
 * per row. On a comment tree of 20 roots plus 60 replies that was roughly 160
 * queries for one page load.
 *
 * {@link #resolveAll} does the same work with at most three queries in total:
 * one for the human authors, one for the agents, one for the agents' owners.
 */
@Component
@RequiredArgsConstructor
public class AuthorResolver {

    private final UserMapper userMapper;
    private final AgentMapper agentMapper;

    /**
     * Author display data for one row.
     */
    @Getter
    public static class AuthorInfo {
        private final String authorName;
        private final String authorAvatar;
        private final String agentOwnerName;
        private final Long agentOwnerId;

        AuthorInfo(String authorName, String authorAvatar, String agentOwnerName, Long agentOwnerId) {
            this.authorName = authorName;
            this.authorAvatar = authorAvatar;
            this.agentOwnerName = agentOwnerName;
            this.agentOwnerId = agentOwnerId;
        }

        static AuthorInfo empty() {
            return new AuthorInfo(null, null, null, null);
        }

        static AuthorInfo system() {
            return new AuthorInfo("SYSTEM", null, null, null);
        }
    }

    /**
     * Batch-resolve authors for a collection of rows.
     *
     * @param rows      the rows to resolve for (posts, comments, ...)
     * @param typeOf    extracts the author type ("HUMAN" / "AGENT" / "SYSTEM")
     * @param idOf      extracts the author id
     * @return lookup keyed by "TYPE:id"; missing authors map to an empty AuthorInfo
     */
    public <T> Map<String, AuthorInfo> resolveAll(Collection<T> rows,
                                                  Function<T, String> typeOf,
                                                  Function<T, Long> idOf) {
        Map<String, AuthorInfo> resolved = new HashMap<>();
        if (rows == null || rows.isEmpty()) {
            return resolved;
        }

        Set<Long> humanIds = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        for (T row : rows) {
            String type = typeOf.apply(row);
            Long id = idOf.apply(row);
            if (id == null || type == null) {
                continue;
            }
            if (AuthorType.HUMAN.getCode().equalsIgnoreCase(type)) {
                humanIds.add(id);
            } else if (AuthorType.AGENT.getCode().equalsIgnoreCase(type)) {
                agentIds.add(id);
            }
        }

        Map<Long, User> users = loadUsers(humanIds);
        Map<Long, Agent> agents = agentIds.isEmpty()
                ? Map.of()
                : agentMapper.selectBatchIds(agentIds).stream()
                        .collect(Collectors.toMap(Agent::getId, Function.identity(), (a, b) -> a));

        // Owners of the agents above, in a single extra query
        Set<Long> ownerIds = agents.values().stream()
                .map(Agent::getOwnerId)
                .filter(java.util.Objects::nonNull)
                .filter(ownerId -> !users.containsKey(ownerId))
                .collect(Collectors.toSet());
        Map<Long, User> owners = loadUsers(ownerIds);

        for (T row : rows) {
            String type = typeOf.apply(row);
            Long id = idOf.apply(row);
            if (type == null || id == null) {
                continue;
            }
            resolved.put(key(type, id), resolveOne(type, id, users, agents, owners));
        }
        return resolved;
    }

    /**
     * Key used by {@link #resolveAll}'s result map.
     */
    public String key(String authorType, Long authorId) {
        return authorType + ":" + authorId;
    }

    /**
     * Single-row resolution, for the paths that genuinely handle one row.
     */
    public AuthorInfo resolve(String authorType, Long authorId) {
        if (authorType == null || authorId == null) {
            return AuthorInfo.empty();
        }
        if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(authorType)) {
            return AuthorInfo.system();
        }
        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(authorType)) {
            User user = userMapper.selectById(authorId);
            return user == null
                    ? AuthorInfo.empty()
                    : new AuthorInfo(user.getUsername(), user.getAvatarUrl(), null, null);
        }
        if (AuthorType.AGENT.getCode().equalsIgnoreCase(authorType)) {
            Agent agent = agentMapper.selectById(authorId);
            if (agent == null) {
                return AuthorInfo.empty();
            }
            User owner = agent.getOwnerId() == null ? null : userMapper.selectById(agent.getOwnerId());
            return new AuthorInfo(agent.getName(), agent.getAvatarUrl(),
                    owner != null ? owner.getUsername() : null, agent.getOwnerId());
        }
        return AuthorInfo.empty();
    }

    private Map<Long, User> loadUsers(Set<Long> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<User> found = userMapper.selectBatchIds(ids);
        Map<Long, User> byId = new HashMap<>();
        for (User user : found) {
            byId.put(user.getId(), user);
        }
        return byId;
    }

    private AuthorInfo resolveOne(String type, Long id,
                                  Map<Long, User> users,
                                  Map<Long, Agent> agents,
                                  Map<Long, User> owners) {
        if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(type)) {
            return AuthorInfo.system();
        }
        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(type)) {
            User user = users.get(id);
            return user == null
                    ? AuthorInfo.empty()
                    : new AuthorInfo(user.getUsername(), user.getAvatarUrl(), null, null);
        }
        if (AuthorType.AGENT.getCode().equalsIgnoreCase(type)) {
            Agent agent = agents.get(id);
            if (agent == null) {
                return AuthorInfo.empty();
            }
            User owner = agent.getOwnerId() == null
                    ? null
                    : owners.getOrDefault(agent.getOwnerId(), users.get(agent.getOwnerId()));
            return new AuthorInfo(agent.getName(), agent.getAvatarUrl(),
                    owner != null ? owner.getUsername() : null, agent.getOwnerId());
        }
        return AuthorInfo.empty();
    }
}
