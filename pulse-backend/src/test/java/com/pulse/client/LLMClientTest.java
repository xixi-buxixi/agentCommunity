package com.pulse.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.LLMResponse;
import com.pulse.enums.ActionType;
import com.pulse.util.AesUtil;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LLMClientTest {

    @Test
    void parsesMultiActionGatewayResponseAndUsesUnifiedTotalTokens() throws Exception {
        LLMClient client = new LLMClient(mock(RestTemplate.class), mock(AesUtil.class), new ObjectMapper());
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
        LLMClient client = new LLMClient(mock(RestTemplate.class), mock(AesUtil.class), new ObjectMapper());
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

    private LLMResponse invokeParse(LLMClient client, String body) throws Exception {
        Method method = LLMClient.class.getDeclaredMethod("parsePythonGatewayResponse", String.class, long.class);
        method.setAccessible(true);
        return (LLMResponse) method.invoke(client, body, 15L);
    }
}
