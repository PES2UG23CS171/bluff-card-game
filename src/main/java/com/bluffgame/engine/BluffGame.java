package com.bluffgame.engine;

import com.bluffgame.model.Card;
import com.bluffgame.model.DeckFactory;
import com.bluffgame.model.GameSettings;
import com.bluffgame.model.Rank;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The rules of Bluff for one game, independent of any transport.
 *
 * <p>A round starts with one player putting one or more face-down cards in the pot while
 * announcing a rank; everyone who follows in that round claims the same rank. Until the next
 * player acts, anyone still holding cards may call bluff:
 * the play is revealed, and whoever was wrong takes the whole pot. Players may pass instead
 * of playing; once everyone else has passed, the pot is set aside and the last player to
 * play opens a new round. Emptying your hand (and surviving the call window) means you are
 * done; the game continues until one player is left holding cards.
 *
 * <p>Every mutating call appends {@link GameEvent}s that the caller drains with
 * {@link #drainEvents()}. The class is not thread-safe; callers serialise access per game.
 */
public final class BluffGame {

    public enum Phase { PLAYING, GAME_OVER }

    /** One player's place at the table. */
    public static final class Seat {
        private final String playerId;
        private final List<Card> hand = new ArrayList<>();
        private boolean connected = true;
        private boolean left;
        private Integer finishPlace;

        private Seat(String playerId) {
            this.playerId = playerId;
        }

        public String playerId() {
            return playerId;
        }

        public List<Card> hand() {
            return Collections.unmodifiableList(hand);
        }

        public int cardCount() {
            return hand.size();
        }

        public boolean connected() {
            return connected;
        }

        public boolean left() {
            return left;
        }

        /** 1 for the first player to empty their hand, 2 for the next, ... or null while still playing. */
        public Integer finishPlace() {
            return finishPlace;
        }

        /** Still competing: holds cards and has neither finished nor left the table. */
        public boolean isActive() {
            return !left && finishPlace == null && !hand.isEmpty();
        }

        /** Can take a turn right now. */
        boolean canAct() {
            return isActive() && connected;
        }
    }

    /** Cards one player put in the pot together with the rank they announced. */
    public record Play(String playerId, List<Card> cards, Rank declaredRank, long playedAt) {

        public int count() {
            return cards.size();
        }

        /** True when every card really is the announced rank (jokers count as anything). */
        public boolean honest() {
            return cards.stream().allMatch(card -> card.matches(declaredRank));
        }
    }

    private final GameSettings settings;
    private final List<Seat> seats = new ArrayList<>();
    private final Map<String, Seat> seatsById = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final List<GameEvent> events = new ArrayList<>();

    private Phase phase = Phase.PLAYING;
    private int round;
    private Rank roundRank;
    private String turnPlayerId;
    private long turnStartedAt;
    private final List<Play> pot = new ArrayList<>();
    private Play lastPlay;
    private boolean challengeOpen;
    private final Set<String> passedThisRound = new LinkedHashSet<>();
    private int setAsideCards;
    private final List<String> finishOrder = new ArrayList<>();
    private String loserId;
    private final Map<String, Boolean> restartVotes = new LinkedHashMap<>();

    private BluffGame(GameSettings settings, List<String> playerIds, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        for (String id : playerIds) {
            if (seatsById.containsKey(id)) {
                throw new GameException("Duplicate player id " + id);
            }
            Seat seat = new Seat(id);
            seats.add(seat);
            seatsById.put(id, seat);
        }
    }

    /**
     * Shuffles the decks, deals {@code cardsPerPlayer} cards to every player in seat order and
     * opens the first round with a randomly chosen starter.
     */
    public static BluffGame start(GameSettings settings, List<String> playerIds, Random random, LongSupplier clock) {
        if (playerIds.size() < 2) {
            throw new GameException("At least two players are needed");
        }
        if (!settings.canDeal(playerIds.size())) {
            throw new GameException(String.format(
                    "Not enough cards: %d players x %d cards needs %d, but %d deck(s) only hold %d",
                    playerIds.size(), settings.cardsPerPlayer(), playerIds.size() * settings.cardsPerPlayer(),
                    settings.decks(), settings.totalCards()));
        }
        List<Card> pile = DeckFactory.build(settings.decks(), settings.jokers());
        Collections.shuffle(pile, random);

        BluffGame game = new BluffGame(settings, playerIds, clock);
        int next = 0;
        for (int i = 0; i < settings.cardsPerPlayer(); i++) {
            for (Seat seat : game.seats) {
                seat.hand.add(pile.get(next++));
            }
        }
        game.seats.forEach(seat -> seat.hand.sort(Card.DISPLAY_ORDER));
        game.events.add(new GameEvent("deal")
                .with("order", List.copyOf(playerIds))
                .with("cardsPerPlayer", settings.cardsPerPlayer())
                .with("pileSize", pile.size()));
        game.beginRound(game.seats.get(random.nextInt(game.seats.size())), "random");
        return game;
    }

    /** Test hook: start with exact hands and a known starter instead of a random deal. */
    static BluffGame startWithHands(GameSettings settings, Map<String, List<Card>> hands, String starterId,
                                    LongSupplier clock) {
        BluffGame game = new BluffGame(settings, new ArrayList<>(hands.keySet()), clock);
        hands.forEach((id, cards) -> {
            game.seatsById.get(id).hand.addAll(cards);
            game.seatsById.get(id).hand.sort(Card.DISPLAY_ORDER);
        });
        game.events.add(new GameEvent("deal")
                .with("order", new ArrayList<>(hands.keySet()))
                .with("cardsPerPlayer", hands.values().iterator().next().size())
                .with("pileSize", hands.values().stream().mapToInt(List::size).sum()));
        game.beginRound(game.requireSeat(starterId), "random");
        return game;
    }

    // ---------------------------------------------------------------- actions

    /** Puts {@code cardIds} from the player's hand into the pot, announcing {@code declared}. */
    public void play(String playerId, List<Integer> cardIds, Rank declared) {
        ensurePlaying();
        Seat seat = requireSeat(playerId);
        requireTurn(seat);
        if (cardIds == null || cardIds.isEmpty()) {
            throw new GameException("Select at least one card to play");
        }
        if (declared == null || !declared.isDeclarable()) {
            throw new GameException("Pick the rank you are claiming");
        }
        if (roundRank != null && declared != roundRank) {
            throw new GameException("This round is being played as " + roundRank.label() + "s");
        }
        Set<Integer> wanted = new LinkedHashSet<>(cardIds);
        if (wanted.size() != cardIds.size()) {
            throw new GameException("The same card was selected twice");
        }
        List<Card> chosen = new ArrayList<>();
        for (int id : wanted) {
            chosen.add(seat.hand.stream()
                    .filter(card -> card.id() == id)
                    .findFirst()
                    .orElseThrow(() -> new GameException("You do not hold that card")));
        }
        requireCallWindowElapsed();

        closeChallenge();
        if (phase == Phase.GAME_OVER) {
            return;
        }
        seat.hand.removeAll(chosen);
        if (roundRank == null) {
            roundRank = declared;
        }
        Play play = new Play(playerId, List.copyOf(chosen), declared, clock.getAsLong());
        pot.add(play);
        lastPlay = play;
        challengeOpen = true;
        events.add(new GameEvent("play")
                .with("playerId", playerId)
                .with("count", play.count())
                .with("rank", declared)
                .with("remaining", seat.hand.size())
                .with("potCount", potCardCount()));
        advanceTurn(seat);
    }

    /** Sits the rest of this round out. Not allowed when the pot is empty. */
    public void pass(String playerId) {
        ensurePlaying();
        Seat seat = requireSeat(playerId);
        requireTurn(seat);
        if (pot.isEmpty()) {
            throw new GameException("You have to play to open the round");
        }
        requireCallWindowElapsed();

        closeChallenge();
        if (phase == Phase.GAME_OVER) {
            return;
        }
        passedThisRound.add(playerId);
        events.add(new GameEvent("pass").with("playerId", playerId));
        if (everyoneElsePassed()) {
            endRoundAllPassed();
            return;
        }
        advanceTurn(seat);
    }

    /** Challenges the most recent play. Whoever was wrong takes the entire pot. */
    public void callBluff(String callerId) {
        ensurePlaying();
        if (lastPlay == null || !challengeOpen) {
            throw new GameException("There is no play to call right now");
        }
        Seat caller = requireSeat(callerId);
        if (callerId.equals(lastPlay.playerId())) {
            throw new GameException("You cannot call your own play");
        }
        if (!caller.isActive()) {
            throw new GameException("Only players still holding cards can call a bluff");
        }
        Seat target = seatsById.get(lastPlay.playerId());
        Play challenged = lastPlay;
        boolean honest = challenged.honest();
        Seat receiver = honest ? caller : target;

        List<Card> potCards = pot.stream().flatMap(play -> play.cards().stream()).toList();
        receiver.hand.addAll(potCards);
        receiver.hand.sort(Card.DISPLAY_ORDER);
        pot.clear();
        lastPlay = null;
        challengeOpen = false;
        passedThisRound.clear();

        events.add(new GameEvent("bluffCalled")
                .with("callerId", callerId)
                .with("targetId", target.playerId)
                .with("cards", challenged.cards())
                .with("rank", challenged.declaredRank())
                .with("honest", honest)
                .with("potCount", potCards.size())
                .with("receiverId", receiver.playerId)
                .with("receiverCards", receiver.hand.size()));

        if (honest && target.hand.isEmpty() && target.finishPlace == null) {
            finish(target);
        }
        if (phase == Phase.GAME_OVER) {
            return;
        }
        beginRound(honest ? target : caller, honest ? "honest" : "caught");
    }

    /**
     * Records a restart vote. Voting opens once the first player has finished (or the game is over).
     *
     * @return true when a majority of the connected players at the table has voted yes
     */
    public boolean vote(String playerId, boolean yes) {
        if (!voteOpen()) {
            throw new GameException("There is nothing to vote on yet");
        }
        requireSeat(playerId);
        restartVotes.put(playerId, yes);
        List<String> eligible = seats.stream()
                .filter(seat -> !seat.left && seat.connected)
                .map(Seat::playerId)
                .toList();
        long yesVotes = eligible.stream().filter(id -> Boolean.TRUE.equals(restartVotes.get(id))).count();
        int needed = eligible.size() / 2 + 1;
        events.add(new GameEvent("vote")
                .with("playerId", playerId)
                .with("yes", yes)
                .with("yesVotes", yesVotes)
                .with("needed", needed)
                .with("voters", eligible.size()));
        return !eligible.isEmpty() && yesVotes >= needed;
    }

    /** Marks a player online or offline. Offline players are skipped in the turn order. */
    public void setConnected(String playerId, boolean connected) {
        Seat seat = seatsById.get(playerId);
        if (seat == null || seat.left || seat.connected == connected) {
            return;
        }
        seat.connected = connected;
        events.add(new GameEvent(connected ? "playerReconnected" : "playerDisconnected").with("playerId", playerId));
        if (phase != Phase.PLAYING) {
            return;
        }
        if (!connected && playerId.equals(turnPlayerId)) {
            skipTurn(seat);
        } else if (connected && turnPlayerId == null) {
            turnPlayerId = playerId;
            emitTurn();
        }
    }

    /** Removes a player for good; their cards leave the game. */
    public void removePlayer(String playerId) {
        Seat seat = seatsById.get(playerId);
        if (seat == null || seat.left) {
            return;
        }
        seat.left = true;
        seat.connected = false;
        setAsideCards += seat.hand.size();
        seat.hand.clear();
        restartVotes.remove(playerId);
        events.add(new GameEvent("playerLeft").with("playerId", playerId));
        if (phase != Phase.PLAYING) {
            return;
        }
        if (lastPlay != null && lastPlay.playerId().equals(playerId)) {
            challengeOpen = false;
        }
        checkGameOver();
        if (phase == Phase.PLAYING && playerId.equals(turnPlayerId)) {
            skipTurn(seat);
        }
    }

    /**
     * Times out the player on turn once their timer has run down. It counts as a pass, so they sit
     * out the rest of the round; if they were meant to open it, the next player opens instead.
     * Driven by the room's clock.
     *
     * @return true when the game changed and events were emitted
     */
    public boolean expireTurn() {
        Long endsAt = turnEndsAt();
        if (endsAt == null || clock.getAsLong() < endsAt) {
            return false;
        }
        Seat seat = seatsById.get(turnPlayerId);
        if (seats.stream().noneMatch(other -> other != seat && other.canAct())) {
            turnStartedAt = clock.getAsLong(); // nobody else could take over, so give them another go
            return false;
        }
        passedThisRound.add(seat.playerId);
        events.add(new GameEvent("turnTimedOut")
                .with("playerId", seat.playerId)
                .with("opening", pot.isEmpty()));
        if (pot.isEmpty()) {
            Seat next = nextActorAfter(seat.playerId);
            if (next == null) {
                // Everybody sat out a round that never got going: start a fresh one with the next player.
                passedThisRound.clear();
                beginRound(nextActorAfter(seat.playerId), "timeout");
                return true;
            }
            turnPlayerId = next.playerId;
            emitTurn();
            return true;
        }
        closeChallenge();
        if (phase == Phase.GAME_OVER) {
            return true;
        }
        if (everyoneElsePassed()) {
            endRoundAllPassed();
            return true;
        }
        advanceTurn(seat);
        return true;
    }

    /** Returns and clears the events accumulated since the last drain. */
    public List<GameEvent> drainEvents() {
        List<GameEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    // ---------------------------------------------------------------- flow

    private void beginRound(Seat starter, String reason) {
        round++;
        roundRank = null;
        lastPlay = null;
        challengeOpen = false;
        passedThisRound.clear();
        if (starter == null) {
            starter = firstActor();
        } else if (!starter.canAct()) {
            starter = nextActorAfter(starter.playerId);
        }
        turnPlayerId = starter == null ? null : starter.playerId;
        events.add(new GameEvent("roundStart")
                .with("round", round)
                .with("starterId", turnPlayerId)
                .with("reason", reason));
        emitTurn();
    }

    private void advanceTurn(Seat from) {
        Seat next = nextActorAfter(from.playerId);
        if (next == null) {
            // Nobody else can act: everyone is finished, gone or offline.
            closeChallenge();
            if (phase == Phase.PLAYING) {
                turnPlayerId = null;
                emitTurn();
            }
            return;
        }
        if (next == from) {
            // The turn came straight back: everybody else is out of this round.
            endRoundAllPassed();
            return;
        }
        turnPlayerId = next.playerId;
        emitTurn();
    }

    private void skipTurn(Seat seat) {
        if (!pot.isEmpty() && everyoneElsePassed()) {
            endRoundAllPassed();
            return;
        }
        Seat next = nextActorAfter(seat.playerId);
        turnPlayerId = next == null ? null : next.playerId;
        emitTurn();
    }

    private void endRoundAllPassed() {
        closeChallenge();
        if (phase == Phase.GAME_OVER) {
            return;
        }
        int count = potCardCount();
        String lastPlayerId = lastPlay == null ? null : lastPlay.playerId();
        setAsideCards += count;
        pot.clear();
        events.add(new GameEvent("potSetAside").with("count", count).with("lastPlayerId", lastPlayerId));
        beginRound(lastPlayerId == null ? null : seatsById.get(lastPlayerId), "allPassed");
    }

    /** The most recent play can no longer be called; if its player emptied their hand they are done. */
    private void closeChallenge() {
        if (!challengeOpen) {
            return;
        }
        challengeOpen = false;
        Seat seat = seatsById.get(lastPlay.playerId());
        if (seat.hand.isEmpty() && seat.finishPlace == null && !seat.left) {
            finish(seat);
        }
    }

    private void finish(Seat seat) {
        finishOrder.add(seat.playerId);
        seat.finishPlace = finishOrder.size();
        events.add(new GameEvent("playerFinished").with("playerId", seat.playerId).with("place", seat.finishPlace));
        if (finishOrder.size() == 1) {
            events.add(new GameEvent("voteOpened"));
        }
        checkGameOver();
    }

    private void checkGameOver() {
        List<Seat> active = seats.stream().filter(Seat::isActive).toList();
        if (active.size() > 1) {
            return;
        }
        phase = Phase.GAME_OVER;
        turnPlayerId = null;
        challengeOpen = false;
        boolean abandoned = finishOrder.isEmpty();
        loserId = abandoned || active.isEmpty() ? null : active.get(0).playerId;
        List<String> standings = new ArrayList<>(finishOrder);
        if (loserId != null) {
            standings.add(loserId);
        }
        events.add(new GameEvent("gameOver")
                .with("loserId", loserId)
                .with("standings", standings)
                .with("reason", abandoned ? "abandoned" : "finished"));
    }

    private boolean everyoneElsePassed() {
        return seats.stream()
                .filter(Seat::canAct)
                .map(Seat::playerId)
                .filter(id -> lastPlay == null || !id.equals(lastPlay.playerId()))
                .allMatch(passedThisRound::contains);
    }

    private void emitTurn() {
        turnStartedAt = clock.getAsLong();
        events.add(new GameEvent("turn")
                .with("playerId", turnPlayerId)
                .with("mustPlay", pot.isEmpty())
                .with("endsAt", turnEndsAt()));
    }

    // ---------------------------------------------------------------- lookups

    /** Can take a turn right now and has not passed this round. */
    private boolean canTakeTurn(Seat seat) {
        return seat.canAct() && !passedThisRound.contains(seat.playerId);
    }

    private Seat firstActor() {
        return seats.stream().filter(this::canTakeTurn).findFirst().orElse(null);
    }

    /** The next seat clockwise from {@code playerId} still in the round; may be that same seat, or null. */
    private Seat nextActorAfter(String playerId) {
        int index = seats.indexOf(seatsById.get(playerId));
        for (int step = 1; step <= seats.size(); step++) {
            Seat candidate = seats.get((index + step) % seats.size());
            if (canTakeTurn(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Seat requireSeat(String playerId) {
        Seat seat = seatsById.get(playerId);
        if (seat == null || seat.left) {
            throw new GameException("You are not part of this game");
        }
        return seat;
    }

    private void requireTurn(Seat seat) {
        if (!seat.playerId.equals(turnPlayerId)) {
            throw new GameException("It is not your turn");
        }
    }

    private void requireCallWindowElapsed() {
        Long endsAt = callWindowEndsAt();
        if (endsAt != null) {
            long remaining = endsAt - clock.getAsLong();
            if (remaining > 0) {
                throw new GameException("Wait " + ((remaining + 999) / 1000) + "s so others can call a bluff");
            }
        }
    }

    private void ensurePlaying() {
        if (phase != Phase.PLAYING) {
            throw new GameException("The game is over");
        }
    }

    // ---------------------------------------------------------------- state

    public GameSettings settings() {
        return settings;
    }

    public Phase phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public List<Seat> seats() {
        return Collections.unmodifiableList(seats);
    }

    public Seat seat(String playerId) {
        return seatsById.get(playerId);
    }

    public String turnPlayerId() {
        return turnPlayerId;
    }

    /** The rank every play in this round has to claim, chosen by whoever opened it; null until the first play. */
    public Rank currentRank() {
        return roundRank;
    }

    /** Whether the player on turn has to play (an empty pot cannot be passed on). */
    public boolean mustPlay() {
        return pot.isEmpty();
    }

    public int potCardCount() {
        return pot.stream().mapToInt(Play::count).sum();
    }

    public Play lastPlay() {
        return lastPlay;
    }

    /** True while the latest play can still be called. */
    public boolean challengeOpen() {
        return challengeOpen;
    }

    /**
     * Epoch millis until which the next player has to wait, or null when there is nothing to wait for
     * (no open play, no call window configured, or nobody besides the player on turn could call).
     */
    public Long callWindowEndsAt() {
        if (lastPlay == null || !challengeOpen || settings.callWindowSeconds() == 0) {
            return null;
        }
        boolean othersCanCall = seats.stream().anyMatch(seat -> seat.isActive()
                && !seat.playerId.equals(lastPlay.playerId())
                && !seat.playerId.equals(turnPlayerId));
        if (!othersCanCall) {
            return null;
        }
        return lastPlay.playedAt() + settings.callWindowSeconds() * 1000L;
    }

    /** Epoch millis at which the player on turn is timed out, or null without a timer or a turn. */
    public Long turnEndsAt() {
        if (phase != Phase.PLAYING || turnPlayerId == null || settings.turnSeconds() == 0) {
            return null;
        }
        return turnStartedAt + settings.turnSeconds() * 1000L;
    }

    public int setAsideCards() {
        return setAsideCards;
    }

    /** Players sitting out the rest of the current round. */
    public Set<String> passedPlayerIds() {
        return Collections.unmodifiableSet(passedThisRound);
    }

    public List<String> finishOrder() {
        return Collections.unmodifiableList(finishOrder);
    }

    public String loserId() {
        return loserId;
    }

    public boolean voteOpen() {
        return !finishOrder.isEmpty() || phase == Phase.GAME_OVER;
    }

    public Map<String, Boolean> restartVotes() {
        return Collections.unmodifiableMap(restartVotes);
    }

    /** Players whose votes count towards a restart. */
    public List<String> voters() {
        return seats.stream().filter(seat -> !seat.left && seat.connected).map(Seat::playerId).toList();
    }
}
