/* Card DOM helpers shared by the table renderer and the animations. */
window.Cards = (() => {
  'use strict';

  const SUITS = {
    S: { symbol: '♠', red: false, name: 'spades' },
    H: { symbol: '♥', red: true, name: 'hearts' },
    D: { symbol: '♦', red: true, name: 'diamonds' },
    C: { symbol: '♣', red: false, name: 'clubs' },
    N: { symbol: '', red: false, name: '' },
  };
  const RANKS = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K'];

  function isJoker(card) {
    return card && card.rank === 'JOKER';
  }

  function faceHtml(card) {
    if (isJoker(card)) {
      return '<div class="corner">★</div><div class="pip joker">JOKER</div><div class="corner br">★</div>';
    }
    const suit = SUITS[card.suit] || SUITS.N;
    const corner = card.rank + '<br>' + suit.symbol;
    return '<div class="corner">' + corner + '</div>'
      + '<div class="pip">' + suit.symbol + '</div>'
      + '<div class="corner br">' + corner + '</div>';
  }

  /**
   * Builds a card element. Pass null for an anonymous face-down card.
   * size: 'sm' (seat piles), 'md' (pot), 'lg' (your hand).
   */
  function make(card, options) {
    const opts = options || {};
    const el = document.createElement('div');
    const classes = ['card', opts.size || 'md'];
    if (opts.faceDown || !card) classes.push('down');
    if (card && SUITS[card.suit] && SUITS[card.suit].red) classes.push('red');
    if (isJoker(card)) classes.push('jk');
    el.className = classes.join(' ');
    if (card) el.dataset.id = card.id;
    el.innerHTML = '<div class="card-inner"><div class="card-face">' + (card ? faceHtml(card) : '')
      + '</div><div class="card-back"></div></div>';
    return el;
  }

  /** Replaces the face of an anonymous card so it can be flipped over to reveal it. */
  function setFace(el, card) {
    el.classList.toggle('red', !!(SUITS[card.suit] && SUITS[card.suit].red));
    el.classList.toggle('jk', isJoker(card));
    el.querySelector('.card-face').innerHTML = faceHtml(card);
    el.dataset.id = card.id;
  }

  function label(card) {
    if (isJoker(card)) return 'Joker';
    return card.rank + (SUITS[card.suit] ? SUITS[card.suit].symbol : '');
  }

  return { SUITS, RANKS, make, setFace, label, isJoker };
})();
