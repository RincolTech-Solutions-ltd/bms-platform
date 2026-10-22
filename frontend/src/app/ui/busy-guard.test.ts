import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The busy guard (#456): a button that writes shows that it is working and cannot be pressed twice.
// Two source checks keep new screens honest. Every `const x = useMutation(...)` reads `x.isPending`
// somewhere in its file (to hand to a BusyButton, directly or through a prop), unless a comment
// `busy-exempt: <reason>` within the three lines above says no button sends it (a save as you type, a
// mark as read). And no plain <button> is disabled by a pending or busy state: that button is a
// BusyButton, so it also shows the spinner and says Working. A button that only waits for the request
// (Cancel, Close) stays plain with `/* busy-exempt: <reason> */` in its tag.

const SRC = fileURLToPath(new URL('../../', import.meta.url));

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

const sources = files(SRC).map((f) => ({ file: relative(SRC, f), text: readFileSync(f, 'utf8') }));

/** The opening tag of every plain <button>, braces balanced so an arrow function's `>` does not end it. */
function buttonTags(text: string): { tag: string; line: number }[] {
  const tags: { tag: string; line: number }[] = [];
  for (const match of text.matchAll(/<button\b/g)) {
    let depth = 0;
    let end = match.index;
    for (; end < text.length; end += 1) {
      const c = text[end];
      if (c === '{') depth += 1;
      else if (c === '}') depth -= 1;
      else if (c === '>' && depth === 0) break;
    }
    tags.push({ tag: text.slice(match.index, end + 1), line: text.slice(0, match.index).split('\n').length });
  }
  return tags;
}

// The busy button itself, and the header refresh button, which reads and has its own turning icon (#430).
const OWN_SPINNER = ['app/ui/BusyButton.tsx', 'app/refresh-button.tsx'];
const BUSY_STATE = /\bdisabled=\{[^}]*(isPending|\bbusy\w*|\w*[Bb]usy\b|submitting|saving|sending|confirming|replying|committing|\bpending\b)/;

describe('busy guard', () => {
  it('finds the mutations it guards', () => {
    expect(sources.filter((s) => s.text.includes('useMutation(')).length).toBeGreaterThan(50);
  });

  it('every mutation hook has its pending state read, or says why no button sends it', () => {
    const missing: string[] = [];
    for (const { file, text } of sources) {
      const lines = text.split('\n');
      lines.forEach((line, i) => {
        const name = /const (\w+) = useMutation\(/.exec(line)?.[1];
        if (!name) return;
        const exempt = lines.slice(Math.max(0, i - 3), i).some((l) => l.includes('busy-exempt:'));
        if (!exempt && !new RegExp(`\\b${name}\\.isPending\\b`).test(text)) missing.push(`${file}:${i + 1} ${name}`);
      });
    }
    expect(missing).toEqual([]);
  });

  it('no plain <button> is disabled by a pending or busy state: that is a BusyButton', () => {
    const plain: string[] = [];
    for (const { file, text } of sources.filter((s) => !OWN_SPINNER.includes(s.file))) {
      for (const { tag, line } of buttonTags(text)) {
        if (BUSY_STATE.test(tag) && !tag.includes('busy-exempt:')) plain.push(`${file}:${line}`);
      }
    }
    expect(plain).toEqual([]);
  });
});
