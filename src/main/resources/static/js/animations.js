/* Table animations. Each game event is animated on top of the current DOM before the
 * new state is rendered; everything drawn here lives in #fx-layer and is thrown away. */
window.FX = (() => {
  'use strict';

  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));
  const SIZES = { sm: [34, 48], md: [56, 80], lg: [74, 104] };
  const EASE = 'cubic-bezier(.2,.7,.2,1)';

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const rand = (a, b) => a + Math.random() * (b - a);
  const plural = (n, word) => n + ' ' + word + (n === 1 ? '' : 's');
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, ch => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));

  function center(el) {
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function visible(el) {
    return el && el.getClientRects().length > 0;
  }

  // ------------------------------------------------------------ anchors

  function potAnchor() {
    return center($('#pot'));
  }

  function deckAnchor() {
    const p = potAnchor();
    return { x: p.x - 110, y: p.y - 10 };
  }

  function discardAnchor() {
    const el = $('#discard-pile');
    if (visible(el)) return center($('.discard-cards', el));
    const t = $('#table').getBoundingClientRect();
    return { x: t.left + 70, y: t.bottom - 50 };
  }

  /** Where a player's cards live on screen: their seat pile, or your hand. */
  function anchorFor(playerId, ctx) {
    if (playerId === ctx.me.id) {
      const r = $('#hand').getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    }
    const pile = $('.seat[data-id="' + playerId + '"] .seat-pile');
    if (pile) return center(pile);
    const seat = $('.seat[data-id="' + playerId + '"]');
    return seat ? center(seat) : potAnchor();
  }

  // ------------------------------------------------------------ fx cards

  function transformOf(pos) {
    return 'translate(' + pos.x + 'px, ' + pos.y + 'px) rotate(' + pos.rot + 'deg) scale(' + pos.scale + ')';
  }

  /** Creates a card in the effects layer, centred on (cx, cy). */
  function spawn(card, size, cx, cy, options) {
    const opts = options || {};
    const el = Cards.make(card, { size, faceDown: opts.faceDown !== false });
    const [w, h] = SIZES[size];
    el._pos = { x: cx - w / 2, y: cy - h / 2, rot: opts.rot || 0, scale: opts.scale || 1 };
    el.style.transform = transformOf(el._pos);
    if (opts.hidden) el.style.opacity = '0';
    $('#fx-layer').appendChild(el);
    return el;
  }

  /** Glides an fx card so that its centre lands on (cx, cy). */
  async function fly(el, cx, cy, options) {
    const opts = options || {};
    const [w, h] = [el.offsetWidth, el.offsetHeight];
    const to = {
      x: cx - w / 2,
      y: cy - h / 2,
      rot: opts.rot == null ? el._pos.rot : opts.rot,
      scale: opts.scale == null ? el._pos.scale : opts.scale,
    };
    if (opts.delay) await sleep(opts.delay);
    el.style.opacity = '1';
    const duration = opts.duration || 450;
    const anim = el.animate(
      [{ transform: transformOf(el._pos) }, { transform: transformOf(to) }],
      { duration, easing: opts.easing || EASE, fill: 'forwards' });
    // A hidden or throttled tab may never report the animation as finished; never let that stall the game.
    await Promise.race([anim.finished.catch(() => {}), sleep(duration + 1500)]);
    el._pos = to;
    el.style.transform = transformOf(to);
    anim.cancel();
  }

  function clearLayer() {
    $('#fx-layer').innerHTML = '';
  }

  // ------------------------------------------------------------ banners and bubbles

  /** Shows a banner for `hold` ms; resolves after `wait` ms so the caller can overlap work. */
  function banner(text, cls, hold, sub, wait) {
    const el = document.createElement('div');
    el.className = 'banner ' + (cls || '');
    el.style.setProperty('--hold', hold + 'ms');
    el.innerHTML = esc(text) + (sub ? '<small>' + esc(sub) + '</small>' : '');
    $('#banner-layer').appendChild(el);
    setTimeout(() => el.remove(), hold + 60);
    return sleep(wait == null ? Math.round(hold * 0.75) : wait);
  }

  function bubble(playerId, text, ctx) {
    const host = playerId === ctx.me.id ? $('#my-info') : $('.seat[data-id="' + playerId + '"]');
    if (!host) return;
    const el = document.createElement('div');
    el.className = 'bubble';
    el.textContent = text;
    host.style.position = host.style.position || 'relative';
    host.appendChild(el);
    setTimeout(() => el.remove(), 1700);
  }

  // ------------------------------------------------------------ event animations

  async function deal(ev, state, ctx) {
    const order = ev.order.filter(id => state.players.some(p => p.id === id && p.seated));
    const perPlayer = ev.cardsPerPlayer;
    const total = order.length * perPlayer;
    if (!total) return;
    const step = Math.max(12, Math.min(75, 3000 / total));
    const src = deckAnchor();
    const stack = [];
    for (let i = 0; i < 6; i++) stack.push(spawn(null, 'md', src.x - i * 0.7, src.y - i * 0.9, { rot: -3 + i }));

    const counts = {};
    const flights = [];
    for (let i = 0; i < perPlayer; i++) {
      order.forEach(pid => {
        const index = flights.length;
        const isMe = pid === ctx.me.id;
        const to = isMe ? ctx.handSlot(i, perPlayer) : anchorFor(pid, ctx);
        const card = spawn(null, 'md', src.x, src.y, { hidden: true });
        flights.push(fly(card, to.x + (isMe ? 0 : rand(-6, 6)), to.y, {
          delay: index * step,
          duration: 360,
          rot: isMe ? 0 : rand(-14, 14),
          scale: isMe ? SIZES.lg[0] / SIZES.md[0] : SIZES.sm[0] / SIZES.md[0],
        }).then(() => {
          counts[pid] = (counts[pid] || 0) + 1;
          ctx.setSeatCount(pid, counts[pid]);
          if (window.Sounds) Sounds.deal();
          if (!isMe) card.remove();
        }));
      });
    }
    await Promise.all(flights);
    await sleep(120);
    stack.forEach(el => el.remove());
    clearLayer();
  }

  async function play(ev, state, previous, ctx) {
    const isMe = ev.playerId === ctx.me.id;
    const pot = potAnchor();
    const sources = [];
    if (isMe && previous && previous.you) {
      const stillHeld = new Set(state.you.hand.map(c => c.id));
      previous.you.hand.filter(c => !stillHeld.has(c.id)).forEach(card => {
        const el = $('#hand .card[data-id="' + card.id + '"]');
        if (el) sources.push({ card, at: center(el), el });
      });
    }
    if (!sources.length) {
      const at = anchorFor(ev.playerId, ctx);
      for (let i = 0; i < ev.count; i++) sources.push({ card: null, at });
    }
    const flights = sources.map((source, i) => {
      const fx = spawn(source.card, isMe && source.card ? 'lg' : 'sm', source.at.x, source.at.y, { faceDown: !source.card });
      if (source.el) source.el.style.visibility = 'hidden';
      if (source.card) setTimeout(() => fx.classList.add('down'), 160 + i * 90);
      const scale = (isMe && source.card ? SIZES.md[0] / SIZES.lg[0] : SIZES.md[0] / SIZES.sm[0]);
      return fly(fx, pot.x + rand(-7, 7), pot.y + rand(-5, 5), {
        delay: i * 90, duration: 480, rot: rand(-24, 24), scale,
      }).then(() => fx);
    });
    const cards = await Promise.all(flights);
    ctx.renderPotNow();
    cards.forEach(c => c.remove());
  }

  async function pass(ev, ctx) {
    bubble(ev.playerId, 'Pass', ctx);
    await sleep(350);
  }

  async function potSetAside(ev, ctx) {
    const targets = $$('#pot-cards .card');
    if (!targets.length) return;
    banner('Everyone passed', 'small', 1300, plural(ev.count, 'card') + ' set aside', 0);
    const dest = discardAnchor();
    const flights = targets.map((el, i) => {
      const at = center(el);
      const fx = spawn(null, 'md', at.x, at.y, { rot: rand(-10, 10) });
      el.style.visibility = 'hidden';
      return fly(fx, dest.x + rand(-4, 4), dest.y, { delay: i * 35, duration: 520, rot: rand(-8, 8), scale: SIZES.sm[0] / SIZES.md[0] })
        .then(() => fx);
    });
    const cards = await Promise.all(flights);
    cards.forEach(c => c.remove());
  }

  async function bluffCalled(ev, state, ctx) {
    const pot = potAnchor();
    const table = $('#table').getBoundingClientRect();
    const n = ev.cards.length;
    banner(ctx.nameOf(ev.callerId) + (ev.callerId === ctx.me.id ? ' call' : ' calls') + ' bluff on ' + ctx.nameOf(ev.targetId) + '!',
      'small', 1400, 'Claimed ' + n + ' × ' + ev.rank, 0);

    // Lift the challenged cards out of the pot and turn them over one by one.
    const spacing = Math.min(70, Math.max(40, (table.width - 80) / Math.max(n, 1)));
    const rowY = table.top + table.height * 0.28;
    const startX = pot.x - ((n - 1) * spacing) / 2;
    const potEls = $$('#pot-cards .card');
    const lastEls = potEls.slice(-n);
    const revealed = ev.cards.map((card, i) => {
      const src = lastEls[i] ? center(lastEls[i]) : pot;
      if (lastEls[i]) lastEls[i].style.visibility = 'hidden';
      return spawn(card, 'md', src.x, src.y, { faceDown: true, rot: rand(-10, 10) });
    });
    await Promise.all(revealed.map((fx, i) => fly(fx, startX + i * spacing, rowY, {
      delay: i * 70, duration: 420, rot: 0, scale: 1.2,
    })));
    await sleep(200);
    for (const fx of revealed) {
      fx.classList.remove('down');
      await sleep(Math.min(260, 900 / n));
    }
    await sleep(600);

    if (window.Sounds) Sounds.verdict(ev.honest);
    await banner(ev.honest ? 'HONEST!' : 'BLUFF!', ev.honest ? 'honest' : 'bluff', 1900,
      ctx.nameOf(ev.receiverId) + (ev.receiverId === ctx.me.id ? ' take ' : ' takes ') + plural(ev.potCount, 'card'), 1100);

    // The whole pot slides over to whoever was wrong.
    const dest = anchorFor(ev.receiverId, ctx);
    const rest = $$('#pot-cards .card').filter(el => el.style.visibility !== 'hidden').map(el => {
      const at = center(el);
      el.style.visibility = 'hidden';
      return spawn(null, 'md', at.x, at.y, { rot: rand(-10, 10) });
    });
    const all = revealed.concat(rest);
    const small = SIZES.sm[0] / SIZES.md[0];
    await Promise.all(all.map((fx, i) => fly(fx, dest.x + rand(-10, 10), dest.y + rand(-6, 6), {
      delay: i * 40, duration: 520, rot: rand(-15, 15), scale: ev.receiverId === ctx.me.id ? 1 : small,
    })));
    all.forEach(c => c.remove());
  }

  async function roundStart(ev, ctx) {
    if (ev.starterId == null) return;
    const mine = ev.starterId === ctx.me.id;
    const why = { honest: 'was honest', caught: 'caught the bluff', allPassed: 'everyone passed', random: 'drawn at random', timeout: 'everyone ran out of time' }[ev.reason];
    await banner('New round', 'small', 1300, (mine ? 'You open' : ctx.nameOf(ev.starterId) + ' opens')
      + (why ? ' (' + why + ')' : ''), 450);
  }

  async function playerFinished(ev, ctx) {
    const mine = ev.playerId === ctx.me.id;
    const place = ev.place + (['th', 'st', 'nd', 'rd'][((ev.place % 100) - 20) % 10] || ['th', 'st', 'nd', 'rd'][ev.place % 100] || 'th');
    await banner((mine ? 'You finished ' : ctx.nameOf(ev.playerId) + ' finished ') + place + '!',
      ev.place === 1 ? 'honest' : '', 2000, ev.place === 1 ? 'First out wins the game' : '', 900);
  }

  async function gameOver(ev, ctx) {
    if (ev.reason === 'abandoned') {
      await banner('Game abandoned', 'small', 1400, '', 500);
      return;
    }
    const mine = ev.loserId === ctx.me.id;
    await banner('Game over', 'bluff', 2000, mine ? 'You are the loser!' : ctx.nameOf(ev.loserId) + ' is the loser', 900);
  }

  // ------------------------------------------------------------ dispatcher

  async function animate(ev, state, previous, ctx) {
    if (!state.game || document.hidden) return;
    try {
      switch (ev.kind) {
        case 'deal': return await deal(ev, state, ctx);
        case 'play': return await play(ev, state, previous, ctx);
        case 'pass': return await pass(ev, ctx);
        case 'potSetAside': return await potSetAside(ev, ctx);
        case 'bluffCalled': return await bluffCalled(ev, state, ctx);
        case 'roundStart': return await roundStart(ev, ctx);
        case 'playerFinished': return await playerFinished(ev, ctx);
        case 'gameOver': return await gameOver(ev, ctx);
        case 'restart': return await banner('New game!', 'honest', 1300, 'Everyone agreed to restart', 500);
        default: return undefined;
      }
    } catch (e) {
      console.error('Animation failed for', ev, e);
      clearLayer();
      return undefined;
    }
  }

  return { animate, clearLayer };
})();
