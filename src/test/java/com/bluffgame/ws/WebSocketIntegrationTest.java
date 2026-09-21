package com.bluffgame.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    /** A tiny client that queues every JSON message it receives. */
    class Client {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final WebSocketSession session;

        Client() throws Exception {
            session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
                    inbox.add(mapper.readTree(message.getPayload()));
                }
            }, URI.create("ws://localhost:" + port + "/ws").toString()).get(5, TimeUnit.SECONDS);
        }

        void send(Map<String, Object> message) throws Exception {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        }

        JsonNode next(String type) throws Exception {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode node = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (node != null && type.equals(node.path("type").asText())) {
                    return node;
                }
            }
            throw new AssertionError("No '" + type + "' message arrived in time");
        }

        JsonNode lastUpdate() throws Exception {
            JsonNode last = next("update");
            JsonNode more;
            while ((more = inbox.poll(150, TimeUnit.MILLISECONDS)) != null) {
                if ("update".equals(more.path("type").asText())) {
                    last = more;
                }
            }
            return last;
        }
    }

    @Test
    void twoBrowsersCanCreateJoinStartAndPlay() throws Exception {
        Client alice = new Client();
        Client bob = new Client();

        alice.send(Map.of("type", "create", "nickname", "Alice"));
        JsonNode welcome = alice.next("welcome");
        String code = welcome.get("roomCode").asText();
        assertThat(welcome.get("token").asText()).isNotBlank();

        bob.send(Map.of("type", "join", "code", code.toLowerCase(), "nickname", "Bob"));
        bob.next("welcome");
        assertThat(alice.lastUpdate().path("state").path("players")).hasSize(2);

        bob.send(Map.of("type", "start"));
        assertThat(bob.next("error").get("message").asText()).contains("Only the host");

        alice.send(Map.of("type", "settings", "settings",
                Map.of("decks", 1, "cardsPerPlayer", 4, "jokers", false, "rankMode", "FREE", "callWindowSeconds", 0)));
        alice.lastUpdate();
        alice.send(Map.of("type", "start"));

        JsonNode aliceState = alice.lastUpdate().path("state");
        JsonNode bobState = bob.lastUpdate().path("state");
        assertThat(aliceState.path("room").path("phase").asText()).isEqualTo("PLAYING");
        assertThat(aliceState.path("you").path("hand")).hasSize(4);
        assertThat(bobState.path("you").path("hand")).hasSize(4);

        String turn = aliceState.path("game").path("turnPlayerId").asText();
        Client onTurn = turn.equals(aliceState.path("you").path("id").asText()) ? alice : bob;
        JsonNode hand = (onTurn == alice ? aliceState : bobState).path("you").path("hand");
        onTurn.send(Map.of("type", "play", "cardIds", new int[] {hand.get(0).get("id").asInt()}, "rank", "Q"));

        JsonNode after = alice.lastUpdate();
        assertThat(after.path("events").toString()).contains("\"kind\":\"play\"");
        assertThat(after.path("state").path("game").path("potCount").asInt()).isEqualTo(1);
        assertThat(after.path("state").path("game").path("currentRank").asText()).isEqualTo("Q");

        alice.send(Map.of("type", "chat", "text", "nice one"));
        assertThat(bob.next("chat").path("message").path("text").asText()).isEqualTo("nice one");

        alice.session.close();
        bob.session.close();
    }
}
