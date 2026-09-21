package com.bluffgame.ws;

import com.bluffgame.room.Outbound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Maps live socket sessions to players so the room layer can address people rather than sockets. */
@Component
public class SessionRegistry implements Outbound {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT = 1024 * 1024;

    /** Which room and player a socket session speaks for. */
    public record Binding(String roomCode, String playerId) {
    }

    private final ObjectMapper mapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String, String> sessionByPlayer = new ConcurrentHashMap<>();

    public SessionRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT));
    }

    /** Forgets a session and returns what it was bound to, if anything. */
    public Binding unregister(String sessionId) {
        sessions.remove(sessionId);
        Binding binding = bindings.remove(sessionId);
        if (binding != null) {
            sessionByPlayer.remove(binding.playerId(), sessionId);
        }
        return binding;
    }

    public Binding binding(String sessionId) {
        return bindings.get(sessionId);
    }

    @Override
    public void attach(String sessionId, String roomCode, String playerId) {
        String previous = sessionByPlayer.put(playerId, sessionId);
        if (previous != null && !previous.equals(sessionId)) {
            bindings.remove(previous);
        }
        bindings.put(sessionId, new Binding(roomCode, playerId));
    }

    @Override
    public void send(String playerId, Object message) {
        String sessionId = sessionByPlayer.get(playerId);
        if (sessionId != null) {
            sendToSession(sessionId, message);
        }
    }

    public void sendToSession(String sessionId, Object message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        } catch (JsonProcessingException e) {
            log.error("Could not serialise message {}", message, e);
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not deliver message to session {}: {}", sessionId, e.getMessage());
        }
    }
}
