package siegebot;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** LLM が返す構造化出力。検証は {@link OrderResolver} が行う。 */
public record LlmDecision(
        @JsonPropertyDescription("Orders to change. Empty when the messages are not instructions for the bots.")
        List<BotOrder> orders,
        @JsonPropertyDescription("Short in-character acknowledgement in the players' language (max 40 chars). Empty string when there is nothing to say.")
        String reply) {

    public record BotOrder(
            @JsonPropertyDescription("Exact bot name from the bot list, or \"*\" for every bot.")
            String bot,
            @JsonPropertyDescription("One of: front, hold, follow.")
            String mode,
            @JsonPropertyDescription("For mode=hold: a place name or alias from the place list. Otherwise empty string.")
            String place,
            @JsonPropertyDescription("For mode=follow: exact teammate name to follow. Otherwise empty string.")
            String player,
            @JsonPropertyDescription("push = chase enemies in a wide area; guard = stay close to the post. Empty string for the default.")
            String stance) {
    }
}
