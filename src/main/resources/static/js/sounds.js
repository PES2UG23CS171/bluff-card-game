/* Sound effects synthesised with the Web Audio API, so no audio files are needed.
 * Browsers only allow sound after a user gesture; the context is unlocked on the first one. */
window.Sounds = (() => {
  'use strict';

  const KEY = 'bluff.muted';
  let ctx = null;
  let muted = false;
  let noiseBuffer = null;
  try { muted = localStorage.getItem(KEY) === '1'; } catch (e) { /* storage blocked */ }

  function context() {
    if (muted) return null;
    if (!ctx) {
      const Ctor = window.AudioContext || window.webkitAudioContext;
      if (!Ctor) return null;
      try { ctx = new Ctor(); } catch (e) { return null; }
    }
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    return ctx;
  }

  function unlock() {
    context();
  }
  ['pointerdown', 'keydown', 'touchend'].forEach(type => document.addEventListener(type, unlock, { passive: true }));

  /** A single oscillator note with a quick attack and exponential decay. */
  function tone(o) {
    const c = context();
    if (!c) return;
    const t0 = c.currentTime + (o.delay || 0);
    const duration = o.duration || 0.2;
    const osc = c.createOscillator();
    osc.type = o.type || 'sine';
    osc.frequency.setValueAtTime(o.freq || 440, t0);
    if (o.to) osc.frequency.exponentialRampToValueAtTime(o.to, t0 + duration);
    const gain = c.createGain();
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.linearRampToValueAtTime(o.gain || 0.2, t0 + (o.attack || 0.005));
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    let node = osc;
    if (o.filter) {
      const filter = c.createBiquadFilter();
      filter.type = o.filter.type || 'lowpass';
      filter.frequency.value = o.filter.freq || 1200;
      filter.Q.value = o.filter.q || 1;
      osc.connect(filter);
      node = filter;
    }
    node.connect(gain);
    gain.connect(c.destination);
    osc.start(t0);
    osc.stop(t0 + duration + 0.05);
  }

  /** Filtered white noise: swishes, thumps and shuffles. */
  function noise(o) {
    const c = context();
    if (!c) return;
    if (!noiseBuffer || noiseBuffer.sampleRate !== c.sampleRate) {
      noiseBuffer = c.createBuffer(1, c.sampleRate, c.sampleRate);
      const data = noiseBuffer.getChannelData(0);
      for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1;
    }
    const t0 = c.currentTime + (o.delay || 0);
    const duration = o.duration || 0.15;
    const src = c.createBufferSource();
    src.buffer = noiseBuffer;
    src.loop = true;
    const filter = c.createBiquadFilter();
    filter.type = o.type || 'bandpass';
    filter.frequency.setValueAtTime(o.freq || 2000, t0);
    if (o.to) filter.frequency.exponentialRampToValueAtTime(o.to, t0 + duration);
    filter.Q.value = o.q || 0.7;
    const gain = c.createGain();
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.linearRampToValueAtTime(o.gain || 0.3, t0 + (o.attack || 0.005));
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + duration);
    src.connect(filter);
    filter.connect(gain);
    gain.connect(c.destination);
    src.start(t0);
    src.stop(t0 + duration + 0.05);
  }

  const fx = {
    /** Cards sliding onto the pile: an airy swish and a soft thump per card. */
    play(count) {
      const n = Math.min(count || 1, 4);
      for (let i = 0; i < n; i++) {
        noise({ duration: 0.16, gain: 0.35, delay: i * 0.09, type: 'bandpass', freq: 2600, to: 900, q: 0.6 });
        tone({ freq: 140, to: 60, type: 'sine', duration: 0.12, gain: 0.25, delay: i * 0.09 + 0.08 });
      }
    },
    /** One card landing during the deal. */
    deal() {
      noise({ duration: 0.05, gain: 0.16, type: 'highpass', freq: 3000, q: 0.5 });
    },
    /** A whole deal at once, for when the animation is skipped. */
    shuffle() {
      noise({ duration: 0.6, gain: 0.25, type: 'bandpass', freq: 1800, to: 3200, q: 0.4 });
    },
    pass() {
      tone({ freq: 320, to: 260, type: 'triangle', duration: 0.12, gain: 0.12 });
    },
    /** Somebody shouts bluff: a low two-note sting. */
    bluffCalled() {
      noise({ duration: 0.3, gain: 0.12, type: 'lowpass', freq: 400 });
      tone({ freq: 196, type: 'sawtooth', duration: 0.35, gain: 0.22, filter: { type: 'lowpass', freq: 900 } });
      tone({ freq: 146.8, type: 'sawtooth', duration: 0.6, gain: 0.22, delay: 0.32, filter: { type: 'lowpass', freq: 800 } });
    },
    /** The play was honest: a bright rising chime. */
    honest() {
      [523.25, 659.25, 783.99, 1046.5].forEach((freq, i) => tone({ freq, duration: 0.5, gain: 0.18, delay: i * 0.1 }));
    },
    /** Caught bluffing: a sagging buzz. */
    bluff() {
      tone({ freq: 220, to: 110, type: 'sawtooth', duration: 0.7, gain: 0.22, filter: { type: 'lowpass', freq: 700 } });
      tone({ freq: 110, to: 55, type: 'square', duration: 0.7, gain: 0.08, delay: 0.05, filter: { type: 'lowpass', freq: 400 } });
    },
    /** Last seconds of your turn. */
    tick() {
      tone({ freq: 1200, duration: 0.06, gain: 0.12 });
    },
    /** A turn ran out. */
    timeout() {
      tone({ freq: 240, to: 120, type: 'square', duration: 0.35, gain: 0.12, filter: { type: 'lowpass', freq: 600 } });
    },
    yourTurn() {
      tone({ freq: 880, duration: 0.25, gain: 0.15 });
      tone({ freq: 1318.5, duration: 0.35, gain: 0.12, delay: 0.12 });
    },
    setAside() {
      noise({ duration: 0.4, gain: 0.2, type: 'lowpass', freq: 1800, to: 300 });
    },
    finished() {
      [659.25, 783.99, 987.77, 1318.5].forEach((freq, i) => tone({ freq, type: 'triangle', duration: 0.45, gain: 0.16, delay: i * 0.12 }));
    },
    gameOver() {
      [392, 349.23, 311.13, 261.63].forEach((freq, i) => tone({ freq, type: 'triangle', duration: 0.5, gain: 0.16, delay: i * 0.18 }));
    },
  };

  function verdict(honest) {
    (honest ? fx.honest : fx.bluff)();
  }

  function setMuted(value) {
    muted = !!value;
    try { localStorage.setItem(KEY, muted ? '1' : '0'); } catch (e) { /* ignore */ }
    if (!muted) unlock();
  }

  return Object.assign({ verdict, setMuted, isMuted: () => muted, toggle: () => setMuted(!muted) }, fx);
})();
