package siegebot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.junit.jupiter.api.Test;

/** 通信をしない範囲で、LLM へのリクエストが組み立てられることを確かめる。 */
class LlmClientTest {

    @Test
    void paramsBuildWithSchemaDerivedFromLlmDecision() {
        StructuredMessageCreateParams<LlmDecision> params = assertDoesNotThrow(
                () -> LlmClient.buildParams("claude-haiku-5-5", "Our side: attack\nMessages:\n[Steve] 前線に出て\n"));
        String raw = params.rawParams().toString();
        assertEquals("claude-haiku-5-5", params.rawParams().model().asString());
        // 構造化出力のスキーマに、LlmDecision の項目が入っている
        for (String field : new String[] {"orders", "reply", "bot", "mode", "place", "player", "stance"}) {
            assertTrue(raw.contains(field), "スキーマに " + field + " がない: " + raw);
        }
    }
}
