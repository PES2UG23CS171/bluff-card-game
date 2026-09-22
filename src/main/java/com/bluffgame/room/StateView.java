package com.bluffgame.room;

import com.bluffgame.engine.BluffGame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON state one particular player is allowed to see: their own hand, everyone else's card counts. */
final class StateView {

    private StateView() {
    }

    static Map<String, Object> build(Room room, RoomPlayer viewer, long now) {
        BluffGame game = room.game();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("serverNow", now);

        Map<String, Object> roomView = new LinkedHashMap<>();
        roomView.put("code", room.code());
        roomView.put("hostId", room.hostId());
        roomView.put("phase", room.phase().name());
        roomView.put("settings", room.settings());
        state.put("room", roomView);

        state.put("players", players(room, game));

        Map<String, Object> you = new LinkedHashMap<>();
        you.put("id", viewer.id());
        you.put("kickVotes", room.kickVotes().entrySet().stream()
                .filter(e -> e.getValue().contains(viewer.id()))
                .map(Map.Entry::getKey)
                .toList());
        you.put("nickname", viewer.nickname());
        you.put("host", room.isHost(viewer.id()));
        boolean seated = room.isSeated(viewer.id());
        you.put("seated", seated);
        you.put("hand", seated ? game.seat(viewer.id()).hand() : List.of());
        state.put("you", you);

        state.put("game", game == null ? null : game(game));
        return state;
    }

    private static List<Map<String, Object>> players(Room room, BluffGame game) {
        List<RoomPlayer> ordered = new ArrayList<>();
        if (game != null) {
            for (BluffGame.Seat seat : game.seats()) {
                if (!seat.left()) {
                    room.player(seat.playerId()).ifPresent(ordered::add);
                }
            }
        }
        for (RoomPlayer player : room.players()) {
            if (!ordered.contains(player)) {
                ordered.add(player);
            }
        }

        List<Map<String, Object>> views = new ArrayList<>();
        for (RoomPlayer player : ordered) {
            BluffGame.Seat seat = game == null ? null : game.seat(player.id());
            boolean seated = seat != null && !seat.left();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", player.id());
            view.put("nickname", player.nickname());
            view.put("connected", player.connected());
            view.put("host", room.isHost(player.id()));
            view.put("seated", seated);
            view.put("cards", seated ? seat.cardCount() : 0);
            view.put("finishPlace", seated ? seat.finishPlace() : null);
            view.put("passed", seated && game.passedPlayerIds().contains(player.id()));
            view.put("vote", seated ? game.restartVotes().get(player.id()) : null);
            java.util.Set<String> eligible = kickVoters(room, player.id());
            java.util.Set<String> votes = room.kickVotes().getOrDefault(player.id(), java.util.Set.of());
            view.put("kickVotes", votes.stream().filter(eligible::contains).count());
            view.put("kickNeeded", RoomService.kickVotesNeeded(eligible.size()));
            view.put("kickable", RoomService.kickVotesNeeded(eligible.size()) <= eligible.size());
            views.add(view);
        }
        return views;
    }

    private static java.util.Set<String> kickVoters(Room room, String targetId) {
        return room.players().stream()
                .filter(RoomPlayer::connected)
                .filter(p -> !p.id().equals(targetId))
                .filter(p -> room.game() == null || room.isSeated(p.id()))
                .map(RoomPlayer::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Map<String, Object> game(BluffGame game) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("phase", game.phase().name());
        view.put("round", game.round());
        view.put("currentRank", game.currentRank());
        view.put("turnPlayerId", game.turnPlayerId());
        view.put("mustPlay", game.mustPlay());
        view.put("turnEndsAt", game.turnEndsAt());
        view.put("turnSeconds", game.settings().turnSeconds());
        view.put("potCount", game.potCardCount());
        BluffGame.Play last = game.lastPlay();
        if (last == null) {
            view.put("lastPlay", null);
        } else {
            Map<String, Object> lastView = new LinkedHashMap<>();
            lastView.put("playerId", last.playerId());
            lastView.put("count", last.count());
            lastView.put("rank", last.declaredRank());
            lastView.put("at", last.playedAt());
            view.put("lastPlay", lastView);
        }
        view.put("challengeOpen", game.challengeOpen());
        view.put("callWindowEndsAt", game.callWindowEndsAt());
        view.put("callWindowSeconds", game.settings().callWindowSeconds());
        view.put("setAsideCards", game.setAsideCards());
        view.put("finishOrder", game.finishOrder());
        view.put("loserId", game.loserId());
        view.put("voteOpen", game.voteOpen());
        view.put("votes", game.restartVotes());
        List<String> voters = game.voters();
        long yes = voters.stream().filter(id -> Boolean.TRUE.equals(game.restartVotes().get(id))).count();
        view.put("yesVotes", yes);
        view.put("votesNeeded", voters.size() / 2 + 1);
        view.put("voters", voters.size());
        return view;
    }
}
