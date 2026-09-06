package com.pulse.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.LLMResponse;
import com.pulse.enums.ActionType;
import com.pulse.service.support.LlmCredentialResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LLMClientTest {

    @Test
    void parsesMultiActionGatewayResponseAndUsesUnifiedTotalTokens() throws Exception {
        LLMClient client = new LLMClient(mock(RestTemplate.class), mock(LlmCredentialResolver.class), new ObjectMapper());
        String body = """
                {
                  "success": true,
                  "reason": "reply then like",
                  "actions": [
                    {"type": "reply", "target_post_id": 88, "content": "use layered memory"},
                    {"type": "like", "target_post_id": 88},
                    {"type": "create_bounty", "title": "Need Redis notes", "description": "Give a concise Redis ranking plan", "reward": 20, "deadline_hours": 48}
                  ],
                  "usage": {
                    "prompt_tokens": 320,
                    "completion_tokens": 112,
                    "total_tokens": 432
                  }
                }
                """;

        LLMResponse response = invokeParse(client, body);

        assertThat(response.getTotalTokens()).isEqualTo(432);
        assertThat(response.getPromptTokens()).isEqualTo(320);
        assertThat(response.getCompletionTokens()).isEqualTo(112);
        assertThat(response.getActions()).hasSize(3);
        assertThat(response.getActions().get(0).getAction()).isEqualTo(ActionType.REPLY);
        assertThat(response.getActions().get(0).getTargetPostId()).isEqualTo(88L);
        assertThat(response.getActions().get(2).getAction()).isEqualTo(ActionType.CREATE_BOUNTY);
        assertThat(response.getActions().get(2).getRewardPoints()).isEqualByComparingTo("20");

        List<AgentActionDecision> decisions = client.convertToDecisions(response);
        assertThat(decisions).extracting(AgentActionDecision::getAction)
                .containsExactly(ActionType.REPLY, ActionType.LIKE, ActionType.CREATE_BOUNTY);
    }

    @Test
    void fallsBackToPromptPlusCompletionTokensWhenTotalTokensMissing() throws Exception {
        LLMClient client = new LLMClient(mock(RestTemplate.class), mock(LlmCredentialResolver.class), new ObjectMapper());
        String body = """
                {
                  "success": true,
                  "action": "post",
                  "content": "hello",
                  "prompt_tokens": 7,
                  "completion_tokens": 5
                }
                """;

        LLMResponse response = invokeParse(client, body);

        assertThat(response.getTotalTokens()).isEqualTo(12);
        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getAction()).isEqualTo(ActionType.POST);
    }

    /**
     * A reply may now name the comment it answers. The field is optional and travels
     * alongside target_post_id, never instead of it: the executor verifies the pair, and
     * a comment id on its own gives it nothing to verify against.
     */
    @Test
    void parsesTheCommentTargetOnAReply() throws Exception {
        LLMClient client = new LLMClient(mock(RestTemplate.class), mock(LlmCredentialResolver.class), new ObjectMapper());
        String body = """
                {
                  "success": true,
                  "actions": [
                    {"type": "reply", "target_post_id": 88, "target_comment_id": 500, "content": "answering you"},
                    {"type": "reply", "target_post_id": 89, "content": "top level"}
                  ],
                  "usage": {"total_tokens": 100}
                }
                """;

        LLMResponse response = invokeParse(client, body);

        assertThat(response.getActions().get(0).getTargetCommentId()).isEqualTo(500L);
        // absent means top level, which is what every agent reply was before this field
        assertThat(response.getActions().get(1).getTargetCommentId()).isNull();
        // and the legacy top-level shape mirrors actions[0], exactly as targetPostId does
        assertThat(response.getTargetCommentId()).isEqualTo(500L);

        assertThat(client.convertToDecisions(response).get(0).getTargetCommentId())
                .isEqualTo(500L);
    }

    /**
     * readLong's existing stance applies unchanged: a non-numeric or non-positive id is
     * a hallucination, and the reply degrades to a top-level comment rather than carrying
     * a pointer nothing can resolve.
     */
    @Test
    void aNonNumericOrNonPositiveCommentTargetIsDropped() throws Exception {
        LLMClient client = new LLMClient(mock(RestTemplate.class), mock(LlmCredentialResolver.class), new ObjectMapper());
        String body = """
                {
                  "success": true,
                  "actions": [
                    {"type": "reply", "target_post_id": 88, "target_comment_id": "500", "content": "a"},
                    {"type": "reply", "target_post_id": 89, "target_comment_id": 0, "content": "b"}
                  ],
                  "usage": {"total_tokens": 100}
                }
                """;

        LLMResponse response = invokeParse(client, body);

        assertThat(response.getActions().get(0).getTargetCommentId()).isNull();
        assertThat(response.getActions().get(1).getTargetCommentId()).isNull();
        // the replies themselves survive: a bad pointer never costs the sentence
        assertThat(response.getActions()).extracting(d -> d.getAction())
                .containsExactly(ActionType.REPLY, ActionType.REPLY);
    }

    private LLMResponse invokeParse(LLMClient client, String body) throws Exception {
        Method method = LLMClient.class.getDeclaredMethod("parsePythonGatewayResponse", String.class, long.class);
        method.setAccessible(true);
        return (LLMResponse) method.invoke(client, body, 15L);
    }
}
