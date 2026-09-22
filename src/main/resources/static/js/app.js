/* Bluff client: connection, state, screens and rendering. Animations live in animations.js. */
(() => {
  'use strict';

  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  const RANKS = Cards.RANKS;
  const AVATAR_COLORS = ['#f5b942', '#5ac8fa', '#ff7ab6', '#7ef29a', '#c58bff', '#ffa26b', '#63e6e2', '#f7f36b'];
  const SESSION_KEY = 'bluff.session';
  const NICK_KEY = 'bluff.nickname';

  const App = {
    ws: null,
    connected: false,
    intent: null,          // message to send as soon as the socket opens
    me: { id: null, token: null, nickname: '' },
    roomCode: null,
    state: null,
    queue: [],
    busy: false,
    selected: new Set(),
    chosenRank: null,
    clockOffset: 0,
    screen: 'home',
    reconnectAttempts: 0,
    reconnectTimer: null,
    windowRaf: null,
    unread: 0,
  };
  window.App = App;

  // ------------------------------------------------------------ helpers

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, ch => (
      { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
  }

  function toast(text, kind) {
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = text;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), 3300);
  }

  function ordinal(n) {
    const s = ['th', 'st', 'nd', 'rd'];
    const v = n % 100;
    return n + (s[(v - 20) % 10] || s[v] || s[0]);
  }

  function avatarColor(id) {
    let h = 0;
    for (const ch of String(id)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    return AVATAR_COLORS[h % AVATAR_COLORS.length];
  }

  function playerById(id, state) {
    const s = state || App.state;
    return s ? s.players.find(p => p.id === id) : null;
  }

  function nameOf(id, state) {
    if (id == null) return 'Nobody';
    if (id === App.me.id) return 'You';
    const p = playerById(id, state);
    return p ? p.nickname : 'Someone';
  }

  function serverNow() {
    return Date.now() + App.clockOffset;
  }

  function plural(n, word) {
    return n + ' ' + word + (n === 1 ? '' : 's');
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => toast('Invite link copied', 'good'), () => toast(text));
    } else {
      toast(text);
    }
  }

  function inviteLink() {
    return location.origin + location.pathname + '?room=' + App.roomCode;
  }

  // ------------------------------------------------------------ storage

  function saveSession() {
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify({ code: App.roomCode, token: App.me.token }));
    } catch (e) { /* private mode */ }
  }

  function loadSession() {
    try {
      return JSON.parse(sessionStorage.getItem(SESSION_KEY) || 'null');
    } catch (e) {
      return null;
    }
  }

  function clearSession() {
    try { sessionStorage.removeItem(SESSION_KEY); } catch (e) { /* ignore */ }
  }

  function savedNickname() {
    try { return localStorage.getItem(NICK_KEY) || ''; } catch (e) { return ''; }
  }

  function rememberNickname(name) {
    try { localStorage.setItem(NICK_KEY, name); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------ networking

  function connect() {
    if (App.ws && (App.ws.readyState === WebSocket.OPEN || App.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(proto + '://' + location.host + '/ws');
    App.ws = ws;
    ws.onopen = () => {
      App.connected = true;
      App.reconnectAttempts = 0;
      $('#reconnecting').classList.add('hidden');
      if (App.intent) {
        send(App.intent);
        App.intent = null;
      }
    };
    ws.onmessage = event => {
      try {
        handleMessage(JSON.parse(event.data));
      } catch (e) {
        console.error('Bad message', e, event.data);
      }
    };
    ws.onclose = () => {
      App.connected = false;
      if (App.roomCode && App.me.token) {
        scheduleReconnect();
      } else if (App.screen !== 'home') {
        showHome('Connection to the server was lost.');
      }
    };
    ws.onerror = () => { /* onclose follows */ };
  }

  function scheduleReconnect() {
    if (App.reconnectTimer) return;
    $('#reconnecting').classList.remove('hidden');
    const delay = Math.min(8000, 800 * Math.pow(2, App.reconnectAttempts++));
    App.reconnectTimer = setTimeout(() => {
      App.reconnectTimer = null;
      App.intent = { type: 'join', code: App.roomCode, nickname: App.me.nickname, token: App.me.token };
      connect();
    }, delay);
  }

  function send(message) {
    if (App.ws && App.ws.readyState === WebSocket.OPEN) {
      App.ws.send(JSON.stringify(message));
    } else {
      toast('Not connected', 'error');
    }
  }

  function open(intent) {
    App.intent = intent;
    if (App.ws && App.ws.readyState === WebSocket.OPEN) {
      send(intent);
      App.intent = null;
    } else {
      connect();
    }
  }

  function handleMessage(msg) {
    switch (msg.type) {
      case 'welcome':
        App.me = { id: msg.playerId, token: msg.token, nickname: msg.nickname };
        App.roomCode = msg.roomCode;
        saveSession();
        rememberNickname(msg.nickname);
        history.replaceState(null, '', location.pathname + '?room=' + msg.roomCode);
        break;
      case 'chatHistory':
        $('#chat-log').innerHTML = '';
        msg.messages.forEach(m => appendChat(m, true));
        scrollChat(true);
        break;
      case 'chat':
        appendChat(msg.message);
        break;
      case 'update':
        App.queue.push(msg);
        pump();
        break;
      case 'kicked':
        clearSession();
        showHome(msg.message);
        break;
      case 'error':
        if (msg.action === 'join' || msg.action === 'create') {
          clearSession();
          App.roomCode = null;
          showHome(msg.message);
        } else {
          toast(msg.message, 'error');
        }
        break;
      default:
        console.warn('Unknown message', msg);
    }
  }

  // ------------------------------------------------------------ update queue

  async function pump() {
    if (App.busy) return;
    App.busy = true;
    try {
      while (App.queue.length) {
        const update = App.queue.shift();
        await applyUpdate(update, App.queue.length > 2 || document.hidden);
      }
    } catch (e) {
      console.error(e);
    } finally {
      App.busy = false;
      refreshControls();
    }
  }

  async function applyUpdate(update, fast) {
    const state = update.state;
    const events = update.events || [];
    const previous = App.state;
    App.clockOffset = state.serverNow - Date.now();

    if (state.room.phase === 'LOBBY') {
      App.state = state;
      showScreen('lobby');
      renderLobby();
      events.forEach(ev => logEvent(ev, state));
      return;
    }

    const entering = App.screen !== 'game';
    if (entering) {
      showScreen('game');
      App.selected.clear();
    }
    // Adopt the new state right away so every renderer sees the same game; animations
    // still get the previous state to know where cards came from.
    App.state = state;
    const dealing = events.some(ev => ev.kind === 'deal');
    if (events.some(ev => ev.kind === 'roundStart')) App.chosenRank = null;
    if (dealing) {
      App.selected.clear();
      renderTable(state, { empty: true });
    } else if (entering || !previous || previous.room.phase === 'LOBBY') {
      renderTable(state);
    }
    for (const ev of events) {
      logEvent(ev, state);
      soundFor(ev, state, !fast);
      if (!fast) {
        await FX.animate(ev, state, previous, ctx());
      }
    }
    renderTable(state, { dealIn: dealing });
  }

  /** Plays the sound for an event. The animation module adds the ones that must line up with a picture. */
  function soundFor(ev, state, animated) {
    switch (ev.kind) {
      case 'play': Sounds.play(ev.count); break;
      case 'pass': Sounds.pass(); break;
      case 'bluffCalled':
        Sounds.bluffCalled();
        if (!animated) Sounds.verdict(ev.honest);
        break;
      case 'potSetAside': Sounds.setAside(); break;
      case 'turnTimedOut': Sounds.timeout(); break;
      case 'playerFinished': Sounds.finished(); break;
      case 'gameOver': if (ev.reason !== 'abandoned') Sounds.gameOver(); break;
      case 'deal': if (!animated) Sounds.shuffle(); break;
      case 'turn': if (ev.playerId === App.me.id && state.room.phase === 'PLAYING') Sounds.yourTurn(); break;
      default: break;
    }
  }

  function updateSoundButtons() {
    const muted = Sounds.isMuted();
    $$('.btn-sound').forEach(b => {
      b.textContent = muted ? '\uD83D\uDD07' : '\uD83D\uDD0A';
      b.title = muted ? 'Sound is off' : 'Sound is on';
    });
  }

  /** What the animation module needs from the app. */
  function ctx() {
    return {
      me: App.me,
      nameOf: id => nameOf(id),
      playerById,
      setSeatCount,
      handSlot,
      renderPotNow: () => renderPot(App.state, {}),
    };
  }

  // ------------------------------------------------------------ screens

  function showScreen(name) {
    if (App.screen === name) return;
    $$('.screen').forEach(el => el.classList.toggle('hidden', el.id !== 'screen-' + name));
    App.screen = name;
    const chat = $('#chat');
    if (name === 'home') {
      chat.classList.add('hidden');
    } else {
      chat.classList.remove('hidden');
      if (window.innerWidth <= 900) chat.classList.add('collapsed');
    }
  }

  function showHome(error) {
    if (App.reconnectTimer) {
      clearTimeout(App.reconnectTimer);
      App.reconnectTimer = null;
    }
    $('#reconnecting').classList.add('hidden');
    clearSession();
    App.roomCode = null;
    App.me = { id: null, token: null, nickname: '' };
    App.state = null;
    App.queue = [];
    App.selected.clear();
    showScreen('home');
    $('#home-error').textContent = error || '';
    history.replaceState(null, '', location.pathname);
  }

  // ------------------------------------------------------------ chat

  function appendChat(m, silent) {
    const log = $('#chat-log');
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    const el = document.createElement('div');
    const time = new Date(m.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    if (m.system) {
      el.className = 'msg system';
      el.textContent = m.text;
    } else {
      el.className = 'msg';
      el.innerHTML = '<span class="who">' + esc(m.nickname) + '</span>' + esc(m.text)
        + '<span class="time">' + time + '</span>';
      if (!silent && m.playerId !== App.me.id && $('#chat').classList.contains('collapsed')) {
        App.unread++;
        const badge = $('#chat-unread');
        badge.textContent = App.unread;
        badge.classList.remove('hidden');
      }
    }
    log.appendChild(el);
    while (log.childElementCount > 300) log.firstChild.remove();
    scrollChat(atBottom);
  }

  function appendLog(text, important) {
    const log = $('#chat-log');
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    const el = document.createElement('div');
    el.className = 'msg log' + (important ? ' important' : '');
    el.textContent = text;
    log.appendChild(el);
    while (log.childElementCount > 300) log.firstChild.remove();
    scrollChat(atBottom);
  }

  function scrollChat(force) {
    const log = $('#chat-log');
    if (force) log.scrollTop = log.scrollHeight;
  }

  function toggleChat(show) {
    const chat = $('#chat');
    const collapsed = chat.classList.contains('collapsed');
    chat.classList.toggle('collapsed', show == null ? !collapsed : !show);
    if (!chat.classList.contains('collapsed')) {
      App.unread = 0;
      $('#chat-unread').classList.add('hidden');
      scrollChat(true);
    }
  }

  function logEvent(ev, state) {
    const n = id => nameOf(id, state);
    switch (ev.kind) {
      case 'deal':
        appendLog('Cards dealt: ' + ev.cardsPerPlayer + ' each from a pile of ' + ev.pileSize + '.');
        break;
      case 'roundStart': {
        const why = { honest: 'was honest', caught: 'caught the bluff', allPassed: 'everyone passed', random: 'drawn at random', timeout: 'everyone ran out of time' }[ev.reason];
        appendLog('New round: ' + n(ev.starterId) + ' open' + (ev.starterId === App.me.id ? '' : 's')
          + (why ? ' (' + why + ')' : '') + '.');
        break;
      }
      case 'turnTimedOut':
        appendLog(n(ev.playerId) + ' ran out of time and ' + (ev.playerId === App.me.id ? 'are' : 'is') + ' out of this round.');
        if (ev.playerId === App.me.id) toast('You ran out of time', 'error');
        break;
      case 'play':
        appendLog(n(ev.playerId) + ' put down ' + ev.count + ' × ' + ev.rank + '.');
        break;
      case 'pass':
        appendLog(n(ev.playerId) + ' passed.');
        break;
      case 'potSetAside':
        appendLog('Everyone passed. ' + plural(ev.count, 'card') + ' set aside.');
        break;
      case 'bluffCalled': {
        const shown = ev.cards.map(Cards.label).join(' ');
        appendLog(n(ev.callerId) + ' called bluff on ' + n(ev.targetId) + ': ' + shown + ' → '
          + (ev.honest ? 'HONEST! ' : 'BLUFF! ') + n(ev.receiverId) + ' take' + (ev.receiverId === App.me.id ? '' : 's') + ' ' + plural(ev.potCount, 'card') + '.', true);
        break;
      }
      case 'playerFinished':
        appendLog(n(ev.playerId) + ' finished ' + ordinal(ev.place) + '!', true);
        break;
      case 'voteOpened':
        appendLog('Vote opened: restart now (a majority decides) or play on until one loser is left.');
        break;
      case 'vote':
        appendLog(n(ev.playerId) + ' voted ' + (ev.yes ? 'to restart' : 'to keep playing') + ' (' + ev.yesVotes + ' yes, ' + ev.needed + ' needed).');
        break;
      case 'gameOver':
        appendLog(ev.reason === 'abandoned'
          ? 'The game ended because too many players left.'
          : 'Game over! ' + n(ev.loserId) + (ev.loserId === App.me.id ? ' are' : ' is') + ' the loser.', true);
        break;
      case 'restart':
        appendLog('New game!', true);
        break;
      case 'turn':
        if (ev.playerId === App.me.id && state.room.phase === 'PLAYING') toast('Your turn!', 'good');
        break;
      default:
        break;
    }
  }

  // ------------------------------------------------------------ lobby

  function deckSize(settings) {
    return settings.decks * (52 + (settings.jokers ? 2 : 0));
  }

  function renderLobby() {
    const s = App.state;
    const me = s.you;
    $('#lobby-code').textContent = s.room.code;
    $('#lobby-count').textContent = '(' + s.players.length + ')';
    $('#lobby-players').innerHTML = s.players.map(p => (
      '<li class="' + (p.connected ? '' : 'offline') + '">'
      + '<div class="avatar" style="background:' + avatarColor(p.id) + '">' + esc(p.nickname[0].toUpperCase()) + '</div>'
      + '<span class="pname">' + esc(p.nickname) + '</span>'
      + (p.host ? '<span class="tag host">host</span>' : '')
      + (p.id === me.id ? '<span class="tag">you</span>' : '')
      + (p.connected ? '' : '<span class="tag offline">offline</span>')
      + kickVoteButton(p, s)
      + (me.host && p.id !== me.id ? '<button class="ghost small kick" data-id="' + p.id + '">Kick</button>' : '')
      + '</li>'
    )).join('');

    const form = $('#settings-form');
    const st = s.room.settings;
    Object.keys(st).forEach(key => {
      const el = form.elements[key];
      if (!el || document.activeElement === el) return;
      if (el.type === 'checkbox') el.checked = !!st[key]; else el.value = st[key];
    });
    Array.from(form.elements).forEach(el => { el.disabled = !me.host; });
    form.elements.cardsPerPlayer.disabled = !me.host || !!st.splitEqually;
    updateSettingsHint();

    const connected = s.players.filter(p => p.connected).length;
    const total = deckSize(st);
    const each = st.splitEqually ? Math.floor(total / Math.max(1, connected)) : st.cardsPerPlayer;
    const need = each * connected;
    let reason = '';
    if (connected < 2) reason = 'Waiting for at least one more player to join...';
    else if (need > total) reason = 'Not enough cards: ' + connected + ' players × ' + st.cardsPerPlayer + ' = ' + need + ', but ' + st.decks + ' deck(s) hold ' + total + '.';
    const btn = $('#btn-start');
    btn.classList.toggle('hidden', !me.host);
    btn.disabled = !!reason;
    $('#start-hint').textContent = me.host
      ? (reason || connected + ' players ready. Each gets ' + each + ' random cards out of ' + total
        + (st.splitEqually && total - need > 0 ? ' (' + (total - need) + ' left over)' : '') + '.')
      : (reason || 'Waiting for the host to start the game...');
  }

  function readSettingsForm() {
    const f = $('#settings-form').elements;
    return {
      decks: Math.min(8, Math.max(1, parseInt(f.decks.value, 10) || 1)),
      cardsPerPlayer: Math.max(1, parseInt(f.cardsPerPlayer.value, 10) || 1),
      jokers: f.jokers.checked,
      splitEqually: f.splitEqually.checked,
      callWindowSeconds: Math.min(30, Math.max(0, parseInt(f.callWindowSeconds.value, 10) || 0)),
      turnSeconds: Math.min(300, Math.max(0, parseInt(f.turnSeconds.value, 10) || 0)),
    };
  }

  function updateSettingsHint() {
    const s = readSettingsForm();
    const players = App.state ? App.state.players.filter(p => p.connected).length : 0;
    const total = deckSize(s);
    const max = players ? Math.floor(total / players) : total;
    $('#settings-form').elements.cardsPerPlayer.disabled = s.splitEqually || !(App.state && App.state.you.host);
    $('#settings-hint').textContent = total + ' cards in the pile'
      + (s.splitEqually
        ? (players ? '; split equally, everyone gets ' + max + ' with ' + players + ' connected.' : '; split equally at the start.')
        : (players ? '; up to ' + max + ' per player with ' + players + ' connected.' : '.'))
      + (s.callWindowSeconds ? ' After each play the next player waits ' + s.callWindowSeconds + 's so others can call bluff.' : ' No waiting after plays.')
      + (s.turnSeconds ? ' A player who does nothing for ' + s.turnSeconds + 's is passed automatically.' : ' No turn time limit.');
  }

  let settingsTimer = null;
  function settingsChanged() {
    if (!App.state || !App.state.you.host) return;
    updateSettingsHint();
    clearTimeout(settingsTimer);
    settingsTimer = setTimeout(() => send({ type: 'settings', settings: readSettingsForm() }), 350);
  }

  // ------------------------------------------------------------ game table

  function renderTable(state, options) {
    const opts = options || {};
    const g = state.game;
    const me = state.you;
    $('#game-code').textContent = state.room.code;
    const rankLabel = $('#game-rank');
    rankLabel.textContent = g ? (g.currentRank ? 'Rank: ' + g.currentRank : 'No rank yet') : '';
    rankLabel.classList.toggle('set', !!(g && g.currentRank));
    $('#game-status').textContent = statusText(state);
    $('#btn-end-game').classList.toggle('hidden', !me.host);

    renderSeats(state, opts);
    renderOrder(state, opts);
    renderPot(state, opts);
    renderSpectators(state);

    const myPlayer = playerById(me.id, state);
    const seated = me.seated && myPlayer;
    $('#spectating').classList.toggle('hidden', !!seated);
    $('#hand').classList.toggle('hidden', !seated);
    $('#controls').classList.toggle('hidden', !seated);
    $('#my-name').textContent = me.nickname;
    if (seated) {
      const count = opts.empty ? 0 : myPlayer.cards;
      $('#my-count').textContent = myPlayer.finishPlace
        ? 'finished ' + ordinal(myPlayer.finishPlace)
        : plural(count, 'card');
      renderHand(opts.empty ? [] : me.hand, { dealIn: opts.dealIn });
    } else {
      $('#my-count').textContent = '';
    }
    renderVotePanel(state);
    renderGameOver(state);
    renderTurnTimer();
    refreshControls(state);
  }

  // ------------------------------------------------------------ turn timer

  let timerInterval = null;
  let lastTickSecond = null;
  let lastTimerEnd = null;

  function stopTimerLoop() {
    if (timerInterval) {
      clearInterval(timerInterval);
      timerInterval = null;
    }
    lastTickSecond = null;
  }

  /** Counts down the turn timer on the seat of the player on turn (and next to your hand when it is you). */
  function renderTurnTimer() {
    const s = App.state;
    const g = s && s.game;
    const endsAt = g && g.phase === 'PLAYING' && App.screen === 'game' ? g.turnEndsAt : null;
    const mine = $('#turn-timer');
    const seatTimers = $$('.seat-timer').concat($$('#order-list .row-timer'));
    if (!endsAt) {
      mine.classList.add('hidden');
      seatTimers.forEach(t => t.classList.add('hidden'));
      stopTimerLoop();
      return;
    }
    if (endsAt !== lastTimerEnd) {
      lastTimerEnd = endsAt;
      lastTickSecond = null;
    }
    const remaining = Math.max(0, Math.ceil((endsAt - serverNow()) / 1000));
    const urgent = remaining <= 10;
    const isMe = g.turnPlayerId === App.me.id;
    mine.classList.toggle('hidden', !isMe);
    mine.textContent = remaining + 's';
    mine.classList.toggle('urgent', urgent);
    seatTimers.forEach(t => {
      const seat = t.closest('.seat') || t.closest('li');
      const on = !!seat && seat.dataset.id === g.turnPlayerId;
      t.classList.toggle('hidden', !on);
      if (on) {
        t.textContent = remaining + 's';
        t.classList.toggle('urgent', urgent);
      }
    });
    if (isMe && remaining <= 5 && remaining > 0 && lastTickSecond !== remaining) {
      lastTickSecond = remaining;
      Sounds.tick();
    }
    if (!timerInterval) timerInterval = setInterval(renderTurnTimer, 250);
  }

  function statusText(state) {
    const g = state.game;
    if (!g) return '';
    if (g.phase === 'GAME_OVER') return 'Game over';
    if (!g.turnPlayerId) return 'Waiting for a player to come back...';
    return g.turnPlayerId === App.me.id ? 'Your turn' : nameOf(g.turnPlayerId, state) + "'s turn";
  }

  function seatPositions(count) {
    const positions = [];
    for (let i = 0; i < count; i++) {
      const t = (i + 1) / (count + 1);
      // Small tables sit along the top; bigger ones spread further down the sides.
      const spread = Math.min(0.5, 0.32 + Math.max(0, count - 4) * 0.09);
      const angle = Math.PI + spread - t * (Math.PI + 2 * spread);
      positions.push({ x: 50 + 43 * Math.cos(angle), y: 50 - 38 * Math.sin(angle) });
    }
    return positions;
  }

  function renderSeats(state, opts) {
    const me = state.you;
    const seated = state.players.filter(p => p.seated);
    const others = me.seated ? seated.filter(p => p.id !== me.id) : seated;
    const container = $('#seats');
    const existing = new Map($$('.seat', container).map(el => [el.dataset.id, el]));
    const positions = seatPositions(others.length);
    others.forEach((p, i) => {
      let el = existing.get(p.id);
      if (!el) {
        el = document.createElement('div');
        el.className = 'seat';
        el.dataset.id = p.id;
        el.innerHTML = '<div class="seat-pile"></div><div class="avatar"></div><div class="seat-name"></div>'
          + '<div class="seat-cards"></div><div class="seat-badges"></div><div class="seat-timer hidden"></div>';
        container.appendChild(el);
      }
      existing.delete(p.id);
      el.style.left = positions[i].x + '%';
      el.style.top = positions[i].y + '%';
      updateSeat(el, p, opts.empty ? 0 : p.cards, state);
    });
    existing.forEach(el => el.remove());
  }

  function updateSeat(el, p, count, state) {
    const g = state.game;
    const avatar = $('.avatar', el);
    avatar.textContent = p.nickname[0].toUpperCase();
    avatar.style.background = avatarColor(p.id);
    $('.seat-name', el).textContent = p.nickname;
    $('.seat-cards', el).textContent = p.finishPlace ? 'finished ' + ordinal(p.finishPlace) : plural(count, 'card');
    setPile($('.seat-pile', el), count, 6, 9, 5);
    const badges = [];
    if (p.host) badges.push('<span class="tag host">host</span>');
    if (!p.connected) badges.push('<span class="tag offline">offline</span>');
    if (p.finishPlace) badges.push('<span class="tag done">' + ordinal(p.finishPlace) + '</span>');
    if (p.passed) badges.push('<span class="tag pass">passed</span>');
    if (g.voteOpen && p.vote != null) badges.push('<span class="tag vote">' + (p.vote ? 'restart' : 'play on') + '</span>');
    if (g.loserId === p.id) badges.push('<span class="tag loser">loser</span>');
    $('.seat-badges', el).innerHTML = badges.join('');
    el.classList.toggle('turn', g.turnPlayerId === p.id);
    el.classList.toggle('offline', !p.connected);
    el.classList.toggle('done', !!p.finishPlace);
    el.classList.toggle('passed', !!p.passed);
  }

  /** Used by the deal animation to tick a seat's count up as cards land. */
  function setSeatCount(playerId, count) {
    if (playerId === App.me.id) {
      $('#my-count').textContent = plural(count, 'card');
      return;
    }
    const el = $('.seat[data-id="' + playerId + '"]');
    if (!el) return;
    $('.seat-cards', el).textContent = plural(count, 'card');
    setPile($('.seat-pile', el), count, 6, 9, 5);
  }

  function setPile(pile, count, max, step, spread) {
    const want = Math.min(count, max);
    while (pile.childElementCount > want) pile.lastChild.remove();
    while (pile.childElementCount < want) pile.appendChild(Cards.make(null, { size: 'sm' }));
    Array.from(pile.children).forEach((c, i) => {
      c.style.left = (i * step) + 'px';
      c.style.transform = 'rotate(' + ((i - (want - 1) / 2) * spread) + 'deg)';
    });
  }

  function potTransform(i) {
    const r = ((i * 47) % 25) - 12;
    const x = ((i * 31) % 13) - 6;
    const y = ((i * 17) % 9) - 4;
    return 'translate(calc(-50% + ' + x + 'px), calc(-50% + ' + y + 'px)) rotate(' + r + 'deg)';
  }

  function renderPot(state, opts) {
    const g = state.game;
    const count = opts.empty ? 0 : g.potCount;
    const potCards = $('#pot-cards');
    const want = Math.min(count, 14);
    while (potCards.childElementCount > want) potCards.lastChild.remove();
    while (potCards.childElementCount < want) {
      const i = potCards.childElementCount;
      const c = Cards.make(null, { size: 'md' });
      c.style.transform = potTransform(i);
      potCards.appendChild(c);
    }
    $('#pot-empty').classList.toggle('hidden', count > 0);
    $('#pot-count').textContent = count ? plural(count, 'card') + ' in the pot' : '';
    const lp = g.lastPlay;
    let label = '';
    if (!opts.empty && lp) {
      label = '<b>' + esc(nameOf(lp.playerId, state)) + '</b> put down <b>' + lp.count + ' × ' + esc(lp.rank) + '</b>';
    } else if (g.mustPlay && g.turnPlayerId && g.phase === 'PLAYING') {
      label = esc(nameOf(g.turnPlayerId, state)) + ' open' + (g.turnPlayerId === App.me.id ? '' : 's') + ' the round';
    }
    $('#pot-label').innerHTML = label;
    renderRoundRank(opts.empty ? null : g.currentRank);

    const discard = $('#discard-pile');
    discard.classList.toggle('hidden', !g.setAsideCards);
    setPile($('.discard-cards', discard), g.setAsideCards, 5, 3, 3);
    $('#discard-count').textContent = plural(g.setAsideCards, 'card') + ' set aside';
  }

  /** Shows the rank the opener claimed on a badge next to the pot, so everybody can see it. */
  function renderRoundRank(rank) {
    const badge = $('#round-rank');
    if (!rank) {
      badge.classList.add('hidden');
      badge.dataset.rank = '';
      return;
    }
    if (badge.dataset.rank !== rank) {
      badge.dataset.rank = rank;
      $('.rr-rank', badge).textContent = rank;
      badge.classList.remove('hidden');
      badge.style.animation = 'none';
      void badge.offsetWidth; // restart the pop animation
      badge.style.animation = '';
    }
  }

  /** The turn order down the side: who plays when, whose turn it is, and how many cards each holds. */
  function renderOrder(state, opts) {
    const g = state.game;
    const seated = state.players.filter(p => p.seated);
    $('#order-list').innerHTML = seated.map((p, i) => {
      const cls = [];
      if (g && g.turnPlayerId === p.id) cls.push('turn');
      if (p.passed) cls.push('passed');
      if (p.finishPlace) cls.push('done');
      if (!p.connected) cls.push('offline');
      if (p.id === App.me.id) cls.push('me');
      const tags = [];
      if (p.finishPlace) tags.push('<span class="tag done ord-tag">' + ordinal(p.finishPlace) + '</span>');
      else if (p.passed) tags.push('<span class="tag pass ord-tag">passed</span>');
      if (!p.connected) tags.push('<span class="tag offline ord-tag">offline</span>');
      if (g && g.loserId === p.id) tags.push('<span class="tag loser ord-tag">loser</span>');
      const cards = opts && opts.empty ? 0 : p.cards;
      return '<li class="' + cls.join(' ') + '" data-id="' + p.id + '">'
        + '<span class="ord-idx">' + (i + 1) + '</span>'
        + '<span class="avatar ord-avatar" style="background:' + avatarColor(p.id) + '">' + esc(p.nickname[0].toUpperCase()) + '</span>'
        + '<span class="ord-name">' + esc(p.nickname) + (p.id === App.me.id ? ' (you)' : '') + '</span>'
        + tags.join('')
        + '<span class="ord-cards">' + cards + '<small> cards</small></span>'
        + '<span class="row-timer timer hidden"></span>'
        + kickVoteButton(p, state)
        + '</li>';
    }).join('');
    const watching = state.players.filter(p => !p.seated);
    const el = $('#order-watching');
    el.classList.toggle('hidden', watching.length === 0);
    el.textContent = 'Joining the next game: ' + watching.map(p => p.nickname).join(', ');
  }

  /** A "vote out" toggle for another player, showing how the vote stands. */
  function kickVoteButton(p, state) {
    if (p.id === App.me.id || !p.kickable) return '';
    const mine = (state.you.kickVotes || []).indexOf(p.id) >= 0;
    const votes = p.kickVotes || 0;
    const label = (mine ? 'Voted out' : 'Vote out') + (votes ? ' ' + votes + '/' + p.kickNeeded : '');
    return '<button type="button" class="ghost small kick-vote' + (mine ? ' active' : '') + '" data-id="' + p.id
      + '" title="Vote to remove this player; ' + p.kickNeeded + ' votes are needed">' + label + '</button>';
  }

  function renderSpectators(state) {
    const watching = state.players.filter(p => !p.seated);
    const el = $('#spectators');
    el.classList.toggle('hidden', watching.length === 0);
    el.textContent = 'Watching: ' + watching.map(p => p.nickname).join(', ');
  }

  // ------------------------------------------------------------ hand

  const COMPACT_HAND = 15; // from this many cards on, the hand uses smaller cards

  /** Layout of an n-card fan inside the hand strip: card size, spacing and where it starts. */
  function handLayout(n) {
    const hand = $('#hand');
    const compact = n >= COMPACT_HAND;
    const probe = hand.querySelector('.card');
    const cardW = probe && probe.offsetWidth ? probe.offsetWidth : (compact ? 58 : 74);
    const cardH = probe && probe.offsetHeight ? probe.offsetHeight : (compact ? 82 : 104);
    const width = hand.clientWidth || 600;
    const spacing = n > 1 ? Math.min(cardW * 0.62, (width - cardW - 8) / (n - 1)) : 0;
    const startX = (width - ((n - 1) * spacing + cardW)) / 2;
    return { compact, cardW, cardH, spacing, startX };
  }

  /** Viewport centre of the i-th card of an n-card hand, used to aim dealt cards. */
  function handSlot(i, n) {
    const r = $('#hand').getBoundingClientRect();
    const { cardW, cardH, spacing, startX } = handLayout(n);
    return { x: r.left + startX + i * spacing + cardW / 2, y: r.bottom - 22 - cardH / 2 };
  }

  function renderHand(hand, options) {
    const opts = options || {};
    const el = $('#hand');
    const existing = new Map($$('.card', el).map(c => [Number(c.dataset.id), c]));
    const n = hand.length;
    el.classList.toggle('compact', n >= COMPACT_HAND);
    const { spacing, startX } = handLayout(n);
    const keep = new Set();
    hand.forEach((card, i) => {
      let c = existing.get(card.id);
      if (!c) {
        c = Cards.make(card, { size: 'lg' });
        c.addEventListener('click', () => toggleCard(card));
        c.addEventListener('dblclick', () => selectAllOfRank(card));
        if (opts.dealIn) {
          c.classList.add('deal-in');
          c.style.animationDelay = (i * 45) + 'ms';
        }
        el.appendChild(c);
      }
      keep.add(card.id);
      const mid = (n - 1) / 2;
      // A gentle arc: the outer cards tilt and sit a little lower, but never far enough to reach the buttons.
      const rot = n > 1 ? (i - mid) * Math.min(2.6, 26 / n) : 0;
      const lift = Math.min(14, Math.pow(Math.abs(i - mid), 2) * Math.min(0.9, 12 / n));
      c.style.left = (startX + i * spacing) + 'px';
      c.style.zIndex = i;
      c.style.setProperty('--rot', rot + 'deg');
      c.style.setProperty('--lift', lift + 'px');
      c.classList.toggle('selected', App.selected.has(card.id));
    });
    existing.forEach((c, id) => { if (!keep.has(id)) c.remove(); });
    existing.forEach((c, id) => { if (!keep.has(id)) App.selected.delete(id); });
  }

  function toggleCard(card) {
    if (!canSelect()) return;
    if (App.selected.has(card.id)) {
      App.selected.delete(card.id);
    } else {
      App.selected.add(card.id);
      suggestRank(card);
    }
    renderHand(App.state.you.hand);
    refreshControls();
  }

  function selectAllOfRank(card) {
    if (!canSelect()) return;
    App.state.you.hand.filter(c => c.rank === card.rank).forEach(c => App.selected.add(c.id));
    suggestRank(card);
    renderHand(App.state.you.hand);
    refreshControls();
  }

  function suggestRank(card) {
    const g = App.state.game;
    if (!g) return;
    if (!g.currentRank && !App.chosenRank && card.rank !== 'JOKER') App.chosenRank = card.rank;
  }

  function canSelect() {
    const s = App.state;
    return s && s.game && s.game.phase === 'PLAYING' && s.you.seated && !App.busy;
  }

  function effectiveRank() {
    const g = App.state && App.state.game;
    if (!g) return null;
    return g.currentRank || App.chosenRank;
  }

  // ------------------------------------------------------------ controls

  function renderRankPicker() {
    const g = App.state && App.state.game;
    if (!g) return;
    const picker = $('#rank-picker');
    if (!picker.childElementCount) {
      RANKS.forEach(r => {
        const b = document.createElement('button');
        b.textContent = r;
        b.dataset.rank = r;
        b.type = 'button';
        b.addEventListener('click', () => {
          App.chosenRank = r;
          refreshControls();
        });
        picker.appendChild(b);
      });
    }
    // Only whoever opens the round picks a rank; afterwards the badge on the table shows it.
    const opening = !g.currentRank && g.turnPlayerId === App.me.id && g.phase === 'PLAYING';
    $('#rank-block').classList.toggle('hidden', !opening);
    const chosen = App.chosenRank;
    Array.from(picker.children).forEach(b => b.classList.toggle('active', b.dataset.rank === chosen));
    $('#rank-label').textContent = 'You open the round. Claim a rank:';
  }

  function refreshControls(stateArg) {
    const s = stateArg || App.state;
    if (!s || !s.game || App.screen !== 'game') return;
    const g = s.game;
    const me = s.you;
    const myPlayer = playerById(me.id, s);
    const active = !!(me.seated && myPlayer && myPlayer.cards > 0 && !myPlayer.finishPlace && g.phase === 'PLAYING');
    const myTurn = active && g.turnPlayerId === me.id;
    const waiting = !!(g.callWindowEndsAt && g.callWindowEndsAt - serverNow() > 0);
    const rank = effectiveRank();
    const n = App.selected.size;

    const play = $('#btn-play');
    play.disabled = !myTurn || App.busy || waiting || n === 0 || !rank;
    play.textContent = n > 0 ? 'Play ' + n + (rank ? ' as ' + rank : '') : 'Play';
    const pass = $('#btn-pass');
    pass.disabled = !myTurn || App.busy || waiting || g.mustPlay;
    pass.title = g.mustPlay ? 'Whoever opens a round has to play' : '';

    const call = $('#btn-call');
    const canCall = active && g.challengeOpen && g.lastPlay && g.lastPlay.playerId !== me.id && !App.busy;
    call.classList.toggle('hidden', !canCall);
    if (canCall) call.textContent = 'Call bluff on ' + nameOf(g.lastPlay.playerId, s) + '!';

    const hint = $('#turn-hint');
    if (!active) hint.textContent = myPlayer && myPlayer.finishPlace ? 'You are done. Sit back and watch.' : '';
    else if (myTurn && waiting) hint.textContent = 'Your turn: others may call bluff first...';
    else if (myTurn) hint.textContent = g.mustPlay ? 'Your turn: open the round!' : 'Your turn: add ' + g.currentRank + 's or pass';
    else hint.textContent = '';

    $('#hand').classList.toggle('locked', !active);
    renderRankPicker();
    renderCallWindow(s);
  }

  function renderCallWindow(state) {
    const g = state.game;
    const bar = $('#call-window');
    const endsAt = g.challengeOpen ? g.callWindowEndsAt : null;
    if (!endsAt || endsAt - serverNow() <= 0) {
      bar.classList.add('hidden');
      return;
    }
    bar.classList.remove('hidden');
    const total = Math.max(1, g.callWindowSeconds * 1000);
    if (App.windowRaf) cancelAnimationFrame(App.windowRaf);
    const tick = () => {
      const remaining = endsAt - serverNow();
      if (remaining <= 0 || App.state !== state && App.state.game && App.state.game.callWindowEndsAt !== endsAt) {
        bar.classList.add('hidden');
        App.windowRaf = null;
        if (remaining <= 0) refreshControls();
        return;
      }
      $('.bar', bar).style.width = (100 * remaining / total) + '%';
      App.windowRaf = requestAnimationFrame(tick);
    };
    tick();
  }

  function renderVotePanel(state) {
    const g = state.game;
    const panel = $('#vote-panel');
    const show = g && g.voteOpen && g.phase === 'PLAYING' && state.you.seated;
    panel.classList.toggle('hidden', !show);
    if (!show) return;
    $('#vote-title').textContent = nameOf(g.finishOrder[0], state) + (g.finishOrder[0] === App.me.id ? ' finished first!' : ' finished first!');
    $('#vote-tally').textContent = g.yesVotes + ' of ' + g.voters + ' said yes; ' + g.votesNeeded + ' needed.';
    const mine = g.votes[state.you.id];
    $('#btn-vote-yes').classList.toggle('active', mine === true);
    $('#btn-vote-no').classList.toggle('active', mine === false);
  }

  function renderGameOver(state) {
    const g = state.game;
    const panel = $('#gameover-panel');
    const show = g && g.phase === 'GAME_OVER';
    panel.classList.toggle('hidden', !show);
    if (!show) return;
    const abandoned = !g.loserId && g.finishOrder.length === 0;
    $('#gameover-title').textContent = abandoned
      ? 'Game abandoned'
      : (g.loserId === App.me.id ? 'You lost!' : nameOf(g.loserId, state) + ' is the loser!');
    const rows = g.finishOrder.map((id, i) => '<li>' + ordinal(i + 1) + ' — ' + esc(nameOf(id, state)) + '</li>');
    if (g.loserId) rows.push('<li class="loser">Loser — ' + esc(nameOf(g.loserId, state)) + '</li>');
    $('#standings').innerHTML = rows.join('');
    const seated = state.you.seated;
    $('#btn-again-yes').classList.toggle('hidden', !seated);
    $('#btn-again-no').classList.toggle('hidden', !seated);
    const mine = g.votes[state.you.id];
    $('#btn-again-yes').classList.toggle('active', mine === true);
    $('#btn-again-no').classList.toggle('active', mine === false);
    $('#gameover-tally').textContent = g.yesVotes + ' of ' + g.voters + ' want to play again; ' + g.votesNeeded + ' needed.';
    $('#btn-back-lobby').classList.toggle('hidden', !state.you.host);
  }

  // ------------------------------------------------------------ actions

  function playSelected() {
    const rank = effectiveRank();
    if (!App.selected.size || !rank) return;
    send({ type: 'play', cardIds: Array.from(App.selected), rank });
    App.selected.clear();
    refreshControls();
  }

  function leaveRoom() {
    send({ type: 'leave' });
    showHome('');
  }

  // ------------------------------------------------------------ wiring

  function wire() {
    const nick = $('#nickname');
    nick.value = savedNickname();
    const params = new URLSearchParams(location.search);
    if (params.get('room')) $('#join-code').value = params.get('room').toUpperCase();

    const nicknameOrWarn = () => {
      const name = nick.value.trim();
      if (!name) {
        $('#home-error').textContent = 'Pick a nickname first.';
        nick.focus();
        return null;
      }
      rememberNickname(name);
      return name;
    };
    $('#btn-create').addEventListener('click', () => {
      const name = nicknameOrWarn();
      if (name) {
        $('#home-error').textContent = '';
        open({ type: 'create', nickname: name });
      }
    });
    const join = () => {
      const name = nicknameOrWarn();
      const code = $('#join-code').value.trim().toUpperCase();
      if (!name) return;
      if (code.length !== 6) {
        $('#home-error').textContent = 'Room codes have six characters.';
        return;
      }
      $('#home-error').textContent = '';
      open({ type: 'join', code, nickname: name, token: null });
    };
    $('#btn-join').addEventListener('click', join);
    $('#join-code').addEventListener('keydown', e => { if (e.key === 'Enter') join(); });
    nick.addEventListener('keydown', e => {
      if (e.key === 'Enter') ($('#join-code').value.trim() ? join : () => $('#btn-create').click())();
    });

    $('#btn-copy').addEventListener('click', () => copyText(inviteLink()));
    $('#btn-leave-lobby').addEventListener('click', leaveRoom);
    $('#btn-leave-game').addEventListener('click', () => {
      if (confirm('Leave this game? Your cards will be removed from play.')) leaveRoom();
    });
    $('#btn-end-game').addEventListener('click', () => {
      if (confirm('End the game for everyone and go back to the lobby?')) send({ type: 'endGame' });
    });
    $('#btn-back-lobby').addEventListener('click', () => send({ type: 'endGame' }));
    $('#btn-start').addEventListener('click', () => send({ type: 'start' }));
    $('#lobby-players').addEventListener('click', e => {
      const vote = e.target.closest('button.kick-vote');
      if (vote) {
        send({ type: 'voteKick', playerId: vote.dataset.id });
        return;
      }
      const btn = e.target.closest('button.kick');
      if (btn && confirm('Remove this player from the room?')) send({ type: 'kick', playerId: btn.dataset.id });
    });
    $('#order-list').addEventListener('click', e => {
      const vote = e.target.closest('button.kick-vote');
      if (vote) send({ type: 'voteKick', playerId: vote.dataset.id });
    });
    $('#settings-form').addEventListener('input', settingsChanged);
    $('#settings-form').addEventListener('change', settingsChanged);
    $('#settings-form').addEventListener('submit', e => e.preventDefault());

    $('#btn-play').addEventListener('click', playSelected);
    $('#btn-pass').addEventListener('click', () => send({ type: 'pass' }));
    $('#btn-call').addEventListener('click', () => send({ type: 'callBluff' }));
    $('#btn-vote-yes').addEventListener('click', () => send({ type: 'vote', yes: true }));
    $('#btn-vote-no').addEventListener('click', () => send({ type: 'vote', yes: false }));
    $('#btn-again-yes').addEventListener('click', () => send({ type: 'vote', yes: true }));
    $('#btn-again-no').addEventListener('click', () => send({ type: 'vote', yes: false }));

    $$('.btn-sound').forEach(b => b.addEventListener('click', () => {
      Sounds.toggle();
      updateSoundButtons();
      if (!Sounds.isMuted()) Sounds.pass();
    }));
    updateSoundButtons();
    $('#btn-toggle-chat').addEventListener('click', () => toggleChat());
    $('#btn-toggle-chat-lobby').addEventListener('click', () => toggleChat());
    $('#btn-close-chat').addEventListener('click', () => toggleChat(false));
    $('#chat-form').addEventListener('submit', e => {
      e.preventDefault();
      const input = $('#chat-input');
      const text = input.value.trim();
      if (text) send({ type: 'chat', text });
      input.value = '';
    });

    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) FX.clearLayer();
    });
    window.addEventListener('resize', () => {
      if (App.state && App.screen === 'game' && App.state.you.seated) renderHand(App.state.you.hand);
    });
    document.addEventListener('keydown', e => {
      if (App.screen !== 'game' || e.target.tagName === 'INPUT') return;
      if (e.key === 'Enter' && !$('#btn-play').disabled) playSelected();
      if (e.key === ' ' && !$('#btn-call').classList.contains('hidden')) {
        e.preventDefault();
        send({ type: 'callBluff' });
      }
    });

    const session = loadSession();
    if (session && session.code && session.token) {
      App.roomCode = session.code;
      App.me.token = session.token;
      App.me.nickname = savedNickname();
      open({ type: 'join', code: session.code, nickname: App.me.nickname || 'Player', token: session.token });
    }
  }

  wire();
})();
