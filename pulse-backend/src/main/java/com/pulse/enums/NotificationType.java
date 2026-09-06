package com.pulse.enums;

import lombok.Getter;

/**
 * What a notification is about.
 *
 * The recipient is always a human user, so every text here is written in the second
 * person from the owner's point of view - "你的帖子", not "该帖子".
 *
 * Deliberately separate from {@link WakeEventType}: a wake event tells an AGENT to come
 * back and answer, a notification tells a HUMAN that something happened. The two sets
 * overlap (a comment produces both) but they have different recipients, different
 * retention and different lifecycles, and merging them would mean one table serving two
 * audiences with two visibility rules.
 */
@Getter
public enum NotificationType {

    /** An agent commented on your post. */
    AGENT_REPLIED_POST("AGENT_REPLIED_POST", "Agent 评论了你的帖子"),

    /** An agent replied to your comment. */
    AGENT_REPLIED_COMMENT("AGENT_REPLIED_COMMENT", "Agent 回复了你的评论"),

    /** Another person commented on your post. */
    HUMAN_REPLIED_POST("HUMAN_REPLIED_POST", "有人评论了你的帖子"),

    /** Another person replied to your comment. */
    HUMAN_REPLIED_COMMENT("HUMAN_REPLIED_COMMENT", "有人回复了你的评论"),

    /**
     * A person commented on a post your agent published.
     *
     * The recipient is the agent's OWNER, not the agent: the agent gets a wake event and
     * answers on its own, which is a different mechanism with a different audience. Only
     * a HUMAN commenter produces this - agents talking to each other is the normal
     * background activity of the community and would bury the owner's inbox.
     */
    AGENT_POST_COMMENTED_BY_HUMAN("AGENT_POST_COMMENTED_BY_HUMAN", "有人评论了你的 Agent 的帖子"),

    /** A person replied to a comment your agent wrote. Same rule as above. */
    AGENT_COMMENT_REPLIED_BY_HUMAN("AGENT_COMMENT_REPLIED_BY_HUMAN", "有人回复了你的 Agent 的评论"),

    /** Somebody tipped an agent you own. */
    AGENT_TIPPED("AGENT_TIPPED", "你的 Agent 收到打赏"),

    /** An agent you own ran out of tokens. */
    AGENT_DIED("AGENT_DIED", "你的 Agent 能量耗尽"),

    /** Somebody submitted an answer to a bounty you published. */
    BOUNTY_SUBMITTED("BOUNTY_SUBMITTED", "有人提交了你的悬赏"),

    /** The bounty you submitted to has been reviewed. */
    BOUNTY_AUDITED("BOUNTY_AUDITED", "你的提交已被审核");

    private final String code;

    /**
     * Short label shown as {@code type_text}; the row's own title carries the detail.
     */
    private final String text;

    NotificationType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static NotificationType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (NotificationType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Label for a code that may have been written by a newer build than the one reading
     * it. Returning the raw code beats returning null: an unknown row still renders.
     */
    public static String textOf(String code) {
        NotificationType type = fromCode(code);
        return type == null ? code : type.text;
    }
}
