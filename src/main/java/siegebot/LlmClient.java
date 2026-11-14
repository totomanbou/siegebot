package siegebot;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Claude API を呼んで、チャットの指示を {@link LlmDecision} に直す。呼び出しは専用スレッドで行う。 */
public final class LlmClient implements AutoCloseable {

    static final String SYSTEM_PROMPT = """
            You command AI soldier bots in a Minecraft castle-siege game. Teammates talk in free-form chat \
            (usually Japanese). Turn what they say into orders for the bots.

            Each bot has one standing order:
            - mode "front": fight at the current front line. The front line moves automatically when a gate \
            opens, so use this for general requests such as "attack", "push", "defend the castle", "go to the front".
            - mode "hold": stay at one named place and defend it. Use only when the speaker names a place from \
            the place list (by name or alias). Put that name in "place".
            - mode "follow": escort a teammate. Put the teammate's exact name in "player". When the speaker says \
            "follow me" / "ついてきて", use the speaker's name (shown in brackets before the message).
            - stance "push": chase enemies in a wide area. stance "guard": stay close to the post and only fight \
            enemies that come near. Leave it empty unless the speaker asks for one of these.

            Rules:
            - Only list bots whose order should change. Bots that are not mentioned keep their current order.
            - Use "*" as the bot name when the instruction is for everyone or names no specific bot.
            - Bot names may be abbreviated or written loosely by players ("ボット1", "1番"); map them to the exact \
            names in the bot list.
            - If the messages are small talk, or are not addressed to the bots, return no orders and an empty reply.
            - If an instruction cannot be expressed (unknown place, something bots cannot do such as building), \
            return no order for it and say so briefly in the reply.
            - The reply is one short line spoken by a bot, in the language the players used, 40 characters or fewer.
            """;

    private final AnthropicClient client;
    private final String model;
    private final ExecutorService executor;

    public LlmClient(String apiKey, String model, int timeoutSeconds) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(1)
                .build();
        this.model = model;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SiegeBot-LLM");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param context 現在の状況と発言（{@link OrderService} が組み立てる）
     * @return LLM の判断。断られた・中身がない場合は空。通信の失敗は例外で完了する
     */
    public CompletableFuture<Optional<LlmDecision>> decide(String context) {
        return CompletableFuture.supplyAsync(() -> client.messages().create(buildParams(model, context))
                .content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst(), executor);
    }

    /** リクエストを組み立てる（{@link LlmDecision} から JSON スキーマを作る。通信はしない）。 */
    static StructuredMessageCreateParams<LlmDecision> buildParams(String model, String context) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(SYSTEM_PROMPT)
                .outputConfig(LlmDecision.class)
                .addUserMessage(context)
                .build();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        client.close();
    }
}
