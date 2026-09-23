# Bluff

By Dhrushaj Achar

Real-time multiplayer **Bluff** (also known as *Cheat* / *I Doubt It*) for the browser,
built with Java 21, Spring Boot 3.5 and plain WebSockets. No accounts, no database:
create a room, share the code, play.

## Features

- Rooms with six-letter codes and shareable invite links; players pick their own nickname.
- Host-configurable rules: number of decks, cards dealt to each player (drawn at random
  from the shuffled decks), jokers on or off (jokers count as any rank), and a "call window"
  that stops the next player from acting for a few seconds so everyone gets a chance to call
  a bluff.
- Whoever opens a round plays any number of cards and announces any rank, whether they hold
  it or not; the rank is shown on the table and everyone else in that round claims the same.
- Anyone still holding cards can call bluff until the next player acts; the pot goes to
  whoever was wrong.
- Pass to sit out the rest of the round; when everybody else has passed, the pot is set aside
  and the last player to play opens the next round.
- Everyone sees how many cards each player holds; only you see your own hand.
- The first player to empty their hand wins. A majority vote restarts the game right away,
  otherwise play continues until only one loser is left.
- A play-order column shows who plays when, whose turn it is and how many cards everyone holds;
  spectators are listed there too and are dealt in when the next game starts.
- Turn timer so a player who walks away is passed automatically instead of stalling the table.
- Vote to remove a disruptive player, in the lobby or mid-game: a majority of the other players
  (at least two votes) sends them out of the room.
- Chat panel that doubles as a game log.
- Sound effects for plays, deals, bluff calls and verdicts, with a mute button.
- Animated dealing, plays, passes, bluff reveals and pot moves.
- Reloading the page puts you straight back in your seat; offline players are skipped and can
  rejoin; late joiners watch and get a seat in the next game.

## Running it

Requirements: JDK 21 (or newer). Maven is bundled through the wrapper.

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>. Everyone else on the same network can join with the
machine's LAN address, e.g. `http://192.168.1.20:8080`, or by pasting the room code.
Use `--server.port=9000` (or `-Dspring-boot.run.arguments=--server.port=9000`) to change the port.

Links shared in chat apps show the game's title, description and a preview image; the
preview URLs are built from the address the request came in on, so they work through tunnels
and proxies that set the usual `X-Forwarded-*` headers.

To build a runnable jar:

```bash
./mvnw package
java -jar target/bluff-card-game-0.1.0-SNAPSHOT.jar
```

To run the tests:

```bash
./mvnw test
```

While working on the front-end, the `dev` profile serves the static files straight from
`src/main/resources/static`, so a browser refresh is enough to see changes:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## How a game goes

1. The host sets the rules in the lobby and presses **Start**. Everyone connected gets a seat.
   Each player is dealt the configured number of cards from the shuffled decks; the rest of the
   pile stays out of the game.
2. A starter is drawn at random and opens round 1 by selecting cards from their hand and
   announcing a rank (A, 2 … 10, J, Q, K). The cards go face down into the pot.
3. Until the next player acts, every other player who still holds cards can **call bluff**. The
   play is revealed: if it was honest the caller takes the entire pot, if it was a lie the liar
   does. The honest player (or the successful caller) then opens a new round.
4. Otherwise the next player either adds cards to the pot claiming the round's rank, or
   **passes** and sits the round out. When everyone else has passed, the turn comes back to the
   last player to play, who can keep adding cards (the others may still call bluff) or pass;
   once everyone has passed, the pot is set aside and that player opens a new round.
5. When you put down your last cards you are done as soon as the next player acts without
   calling (or calls and finds you honest). The first player out wins; the vote to restart opens
   at that moment and passes as soon as a majority says yes. Otherwise the game continues until a
   single player is left holding cards: the loser.

## Settings

| Setting | Meaning |
| --- | --- |
| Decks | How many 52-card decks are shuffled together (1–8). |
| Cards per player | How many cards each player is dealt from the shuffled pile. The lobby shows the maximum for the current number of players. |
| Split equally | Ignore the number above and deal the whole pile out in equal shares to whoever is present when the game starts; leftovers stay out. |
| Jokers | Adds two jokers per deck. A joker matches whatever rank was announced. |
| Call window | Seconds the next player has to wait after a play so others can call bluff (0–30; 0 disables the wait). Calling is never delayed. |
| Turn timer | Seconds a player has to act on their turn (10–300, or 0 for no limit). When it runs out it counts as a pass: they sit out the rest of the round, and if they were meant to open it the next player opens instead. Must be longer than the call window. |

## Project layout

```
src/main/java/com/bluffgame
├── model/    Card, Rank, Suit, DeckFactory, GameSettings
├── engine/   BluffGame: the rules, independent of any transport; emits GameEvents
├── room/     Room, RoomPlayer, RoomService (lobbies, seats, chat, per-player state views)
└── ws/       WebSocket endpoint (/ws), session registry and configuration
src/main/resources/static
├── index.html, css/style.css
└── js/cards.js (card DOM), js/animations.js (table effects), js/app.js (client)
```

Every mutation of a room runs under the room's lock, then each connected player receives an
`update` message with the events that happened (for animations) and their personal view of
the state (their own hand, everyone else's card counts).

### WebSocket protocol

Client → server messages are JSON objects with a `type`:

`create {nickname}`, `join {code, nickname, token?}`, `settings {settings}`, `start`,
`play {cardIds, rank}`, `pass`, `callBluff`, `vote {yes}`, `chat {text}`, `kick {playerId}`,
`voteKick {playerId}`, `endGame`, `leave`.

Server → client: `welcome {playerId, token, roomCode, nickname}`, `update {events, state}`,
`chat {message}`, `chatHistory {messages}`, `kicked {message}`, `error {message, action}`.

## Tests

- `BluffGameTest` scripts hands through every rule: dealing, turn order, the round rank,
  passing and set-aside, honest and caught calls, jokers, the call window, finishing, game over,
  the restart vote and offline/departed players.
- `RoomServiceTest` covers rooms, joining, token reconnection, host powers, spectators, chat
  history, host hand-over and clean-up.
- `WebSocketIntegrationTest` boots the app and drives two real socket clients through a game.
