package com.bluffgame.ws;

import com.bluffgame.engine.GameException;
import com.bluffgame.model.GameSettings;
import com.bluffgame.model.Rank;
import com.bluffgame.room.RoomService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The single WebSocket endpoint. Every client message is a JSON object with a {@code type};
 * responses are {@code welcome}, {@code update}, {@code chat}, {@code chatHistory},
 * {@code kicked} or {@code error} objects.
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private final RoomService rooms;
    private final SessionRegistry registry;
    private final ObjectMapper mapper;

    public GameWebSocketHandler(RoomService rooms, SessionRegistry registry, ObjectMapper mapper) {
        this.rooms = rooms;
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionRegistry.Binding binding = registry.unregister(session.getId());
        if (binding != null) {
            rooms.disconnect(binding.roomCode(), binding.playerId(), session.getId());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String type = "";
        try {
            JsonNode msg = mapper.readTree(message.getPayload());
            type = msg.path("type").asText("");
            dispatch(session, type, msg);
        } catch (GameException | IllegalArgumentException e) {
            registry.sendToSession(session.getId(), Map.of("type", "error", "message", e.getMessage(), "action", type));
        } catch (Exception e) {
            log.error("Failed to handle '{}' from session {}", type, session.getId(), e);
            registry.sendToSession(session.getId(), Map.of("type", "error", "message", "Something went wrong on the server", "action", type));
        }
    }

    private void dispatch(WebSocketSession session, String type, JsonNode msg) {
        String sessionId = session.getId();
        switch (type) {
            case "create" -> rooms.create(msg.path("nickname").asText(), sessionId);
            case "join" -> rooms.join(
                    msg.path("code").asText("").strip().toUpperCase(Locale.ROOT),
                    msg.path("nickname").asText(),
                    msg.path("token").isTextual() ? msg.path("token").asText() : null,
                    sessionId);
            default -> {
                SessionRegistry.Binding binding = registry.binding(sessionId);
                if (binding == null) {
                    throw new GameException("Join a room first");
                }
                dispatchInRoom(binding.roomCode(), binding.playerId(), type, msg);
            }
        }
    }

    private void dispatchInRoom(String code, String playerId, String type, JsonNode msg) {
        switch (type) {
            case "settings" -> rooms.updateSettings(code, playerId, parseSettings(msg));
            case "start" -> rooms.startGame(code, playerId);
            case "endGame" -> rooms.endGame(code, playerId);
            case "play" -> rooms.play(code, playerId, parseCardIds(msg.path("cardIds")), Rank.fromLabel(msg.path("rank").asText(null)));
            case "pass" -> rooms.pass(code, playerId);
            case "callBluff" -> rooms.callBluff(code, playerId);
            case "vote" -> rooms.vote(code, playerId, msg.path("yes").asBoolean(false));
            case "chat" -> rooms.chat(code, playerId, msg.path("text").asText(""));
            case "kick" -> rooms.kick(code, playerId, msg.path("playerId").asText(""));
            case "leave" -> rooms.leave(code, playerId);
            default -> throw new GameException("Unknown message type '" + type + "'");
        }
    }

    private static GameSettings parseSettings(JsonNode msg) {
        JsonNode s = msg.path("settings");
        return new GameSettings(
                s.path("decks").asInt(1),
                s.path("cardsPerPlayer").asInt(1),
                s.path("jokers").asBoolean(true),
                s.path("callWindowSeconds").asInt(5),
                s.path("turnSeconds").asInt(30));
    }

    private static List<Integer> parseCardIds(JsonNode node) {
        List<Integer> ids = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> ids.add(n.asInt()));
        }
        return ids;
    }
}
