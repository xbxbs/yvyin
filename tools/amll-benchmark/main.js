import { LyricPlayer } from '@applemusic-like-lyrics/core';
import '@applemusic-like-lyrics/core/style.css';

const track = await fetch('./track.json').then(response => response.json());
const lines = track.lines.filter(line => line.startMs >= 24863).map(line => ({
  startTime: line.startMs,
  endTime: line.endMs,
  words: line.words.map(word => ({word: word.text, startTime: word.startMs, endTime: word.endMs})),
  translatedLyric: '', romanLyric: '', isBG: false, isDuet: false,
}));
const player = new LyricPlayer();
document.querySelector('#lyrics').append(player.getElement());
player.setAlignAnchor('top');
player.setAlignPosition(0.06);
player.setLyricLines(lines, 34500);
player.setCurrentTime(34500, true);
player.pause();
let time = 34500;
let previous = performance.now();
let automatic = false;
window.benchmark = {
  player,
  seek(next) { automatic = false; player.pause(); time = next; player.setCurrentTime(time, true); },
  play() { automatic = true; previous = performance.now(); player.resume(); },
  pause() { automatic = false; player.pause(); },
  sample() {
    return [...player.getElement().querySelectorAll('[class*="lyricLineWrapper"]')].map(element => ({
      text: element.textContent,
      y: element.getBoundingClientRect().y,
      height: element.getBoundingClientRect().height,
      transform: getComputedStyle(element).transform,
      filter: getComputedStyle(element).filter,
    }));
  },
};
function frame(now) {
  const delta = Math.min(now - previous, 50);
  if (automatic) time += now - previous;
  previous = now;
  player.setCurrentTime(time);
  player.update(delta);
  document.querySelector('#stamp').textContent = `AMLL 0.6.0 / ${(time / 1000).toFixed(2)}s`;
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
window.ready = true;
