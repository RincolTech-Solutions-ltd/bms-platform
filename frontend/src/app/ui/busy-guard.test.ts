import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The busy guard (#456): a button that writes shows that it is working and cannot be pressed twice.
// Two source checks keep new screens honest. Both are heuristics over the source text, not a type or
// data-flow check: check 1 asks only that the pending flag appears inside some `busy=` attribute (or an
// alias of one) somewhere in the same file, not that it is the busy of the button that sends the
// mutation, so a wrong pairing in one file still passes. Review the pairing by eye.
//
// 1. A mutation sent from a press is busy. A mutation is `useMutation(` (with or without generics,
//    named or destructured) or a call of a local hook that returns one (`return useMutation(`). When its
//    mutate or mutateAsync runs from a handler attribute (onClick, onSubmit, or a component's own
//    callback such as onSave), directly or through a function of the same file, its pending flag must
//    reach a `busy={...}` attribute in that file: a BusyButton's, or a busy prop of a component that
//    hands it to one (SaveBar, a View). A local alias (`const busy = save.isPending || ...`) counts.
//    A mutation that needs no busy button carries `// busy-exempt: <reason>` in the run of `//` comment
//    lines directly above its `const ... = useMutation(` line (a comment with no reason does not count);
//    anything else is an entry in ALLOWED below with its reason.
// 2. No plain <button> is disabled by a pending or busy state: that button is a BusyButton, so it also
//    shows the spinner. A button that only waits for the request (Cancel, Close) stays plain with
//    `/* busy-exempt: <reason> */` in its tag.

const SRC = fileURLToPath(new URL('../../', import.meta.url));

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

const sources = files(SRC).map((f) => ({ file: relative(SRC, f), text: readFileSync(f, 'utf8') }));

/** The text from `open` (an opening brace or parenthesis) to its partner, both included. */
function balanced(text: string, open: number): string {
  const pairs: Record<string, string> = { '{': '}', '(': ')' };
  const opener = text[open] ?? '';
  const closer = pairs[opener];
  if (!closer) return '';
  let depth = 0;
  for (let i = open; i < text.length; i += 1) {
    if (text[i] === opener) depth += 1;
    else if (text[i] === closer && (depth -= 1) === 0) return text.slice(open, i + 1);
  }
  return text.slice(open);
}

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

/** The local hooks that return a mutation: `function useX(...) { ... return useMutation(`, or `const useX = () => useMutation(`. */
function mutationHooks(text: string): string[] {
  const hooks: string[] = [];
  for (const m of text.matchAll(/(?:return|=>)\s*useMutation\s*[<(]/g)) {
    const before = text.slice(0, m.index);
    const name = [...before.matchAll(/(?:function\s+(use\w+)|const\s+(use\w+)\s*=)/g)].at(-1);
    const hook = name?.[1] ?? name?.[2];
    if (hook) hooks.push(hook);
  }
  return hooks;
}

type Mutation = { name: string; line: number; index: number; sends: string[]; pending: string | null; exempt: boolean };

const EXEMPT = /^\/\/\s*busy-exempt:\s*\S/;

/** True when the `//` comment lines directly above the line at `index` hold an `exempt` comment (busy or toast) with a reason. */
function exempted(text: string, index: number, exempt: RegExp = EXEMPT): boolean {
  const above = text.slice(0, index).split('\n').slice(0, -1).reverse();
  for (const line of above) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('//')) return false;
    if (exempt.test(trimmed)) return true;
  }
  return false;
}

/** Every mutation a file holds, with the expressions that send it and the one that reads its pending flag. */
function mutations(text: string, hooks: string[]): Mutation[] {
  const callee = ['useMutation', ...hooks].join('|');
  const found: Mutation[] = [];
  const at = (index: number) => text.slice(0, index).split('\n').length;
  for (const m of text.matchAll(new RegExp(`const\\s+(\\w+)\\s*=\\s*(?:${callee})\\s*[<(]`, 'g'))) {
    const name = m[1] ?? '';
    found.push({ name, line: at(m.index), index: m.index, sends: [`${name}.mutate`, `${name}.mutateAsync`], pending: `${name}.isPending`, exempt: exempted(text, m.index) });
  }
  for (const m of text.matchAll(new RegExp(`const\\s*\\{([^}]*)\\}\\s*=\\s*(?:${callee})\\s*[<(]`, 'g'))) {
    const fields = new Map<string, string>();
    for (const part of (m[1] ?? '').split(',')) {
      const [key, alias] = part.split(':').map((s) => s.trim());
      if (key) fields.set(key, alias || key);
    }
    const sends = ['mutate', 'mutateAsync'].flatMap((k) => (fields.has(k) ? [fields.get(k) ?? k] : []));
    found.push({ name: sends[0] ?? 'mutation', line: at(m.index), index: m.index, sends, pending: fields.get('isPending') ?? null, exempt: exempted(text, m.index) });
  }
  return found;
}

const DOM_INPUT = /^on(Change|Input|Blur|Focus|Key\w*|Pointer\w*|Mouse\w*|Scroll|Load|Error|Toggle|Select|Paste|Drag\w*|Drop|Touch\w*|Animation\w*|Transition\w*)$/;

/** The code that runs on a press: every handler attribute but the typing and focus ones, with the local functions they call. */
function pressCode(text: string): string {
  const parts: string[] = [];
  for (const m of text.matchAll(/\s(on[A-Z]\w*)=\{/g)) {
    if (DOM_INPUT.test(m[1] ?? '')) continue;
    parts.push(balanced(text, m.index + m[0].length - 1));
  }
  return follow(text, parts);
}

/**
 * `parts` with the local functions they call, three calls deep: `onSubmit={submit}` and `function submit() { save.mutate() }`.
 * With `props`, a name that is a callback prop (`onSaved(x)`) also brings the expressions handed to it in the same file.
 */
function follow(text: string, parts: string[], props = false): string {
  const seen = new Set<string>();
  for (let depth = 0; depth < 3; depth += 1) {
    const joined = parts.join('\n');
    for (const [, name] of joined.matchAll(/\b([a-z]\w*)\b/g)) {
      if (!name || seen.has(name)) continue;
      seen.add(name);
      if (props && /^on[A-Z]/.test(name)) {
        for (const m of text.matchAll(new RegExp(`\\s${name}=\\{`, 'g'))) parts.push(balanced(text, m.index + m[0].length - 1));
      }
      const decl = new RegExp(`(?:function\\s+${name}\\s*\\(|const\\s+${name}\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>)`).exec(text);
      if (!decl) continue;
      const rest = text.slice(decl.index + decl[0].length);
      const body = /^\s*\{/.test(rest) || decl[0].startsWith('function') ? rest.indexOf('{') : -1;
      parts.push(body >= 0 ? balanced(rest, body) : (rest.split('\n')[0] ?? ''));
    }
  }
  return parts.join('\n');
}

/** Every `busy*={...}` (or `*Busy={...}`) attribute expression, and every local alias of a flag that reaches one. */
function busyCode(text: string): string {
  const attrs = [...text.matchAll(/\s(\w*[Bb]usy\w*)=\{/g)].map((m) => balanced(text, m.index + m[0].length - 1)).join('\n');
  const aliases = [...text.matchAll(/const\s+(\w+)\s*=\s*([^;\n]+)/g)]
    .filter(([, name]) => name && new RegExp(`\\b${name}\\b`).test(attrs))
    .map(([, , value]) => value)
    .join('\n');
  return `${attrs}\n${aliases}`;
}

const has = (code: string, expr: string) => new RegExp(`(^|[^\\w.])${expr.replace(/\./g, '\\.')}\\b`).test(code);

/** The mutations of one file that a press sends. */
function pressed(text: string, hooks: string[] = mutationHooks(text)): Mutation[] {
  const press = pressCode(text);
  return mutations(text, hooks).filter((m) => m.sends.some((send) => has(press, send)));
}

/** The mutations of one file that a press sends while nothing shows them busy. */
function unguarded(text: string, hooks: string[] = mutationHooks(text)): string[] {
  const busy = busyCode(text);
  return pressed(text, hooks)
    .filter((m) => !m.exempt && (!m.pending || !has(busy, m.pending)))
    .map((m) => `${m.line} ${m.name}`);
}

// file:mutation -> why no busy flag is needed. Keep each reason in plain words.
const ALLOWED: Record<string, string> = {};

const OWN_SPINNER = ['app/ui/BusyButton.tsx', 'app/refresh-button.tsx'];
const BUSY_STATE = /\bdisabled=\{[^}]*(isPending|\bbusy\w*|\w*[Bb]usy\b|submitting|saving|sending|confirming|replying|committing|\bpending\b)/;

function plainBusyButtons(text: string): number[] {
  return buttonTags(text)
    .filter(({ tag }) => BUSY_STATE.test(tag) && !tag.includes('busy-exempt:'))
    .map(({ line }) => line);
}

const hooks = sources.flatMap((s) => mutationHooks(s.text));

describe('busy guard', () => {
  it('finds the mutations it guards', () => {
    expect(sources.filter((s) => /useMutation\s*[<(]/.test(s.text)).length).toBeGreaterThan(50);
    expect(hooks).toContain('useSaveReceipt');
    expect(sources.flatMap(({ text }) => pressed(text, hooks)).length).toBeGreaterThan(120);
  });

  it('every mutation a press sends hands its pending flag to a busy button', () => {
    const missing = sources.flatMap(({ file, text }) =>
      unguarded(text, hooks)
        .map((m) => `${file}:${m}`)
        .filter((key) => !ALLOWED[key.replace(/:\d+ /, ':')]),
    );
    expect(missing).toEqual([]);
  });

  it('no plain <button> is disabled by a pending or busy state: that is a BusyButton', () => {
    const plain = sources
      .filter((s) => !OWN_SPINNER.includes(s.file))
      .flatMap(({ file, text }) => plainBusyButtons(text).map((line) => `${file}:${line}`));
    expect(plain).toEqual([]);
  });
});

describe('busy guard on fixtures', () => {
  const save = `const save = useMutation({ mutationFn: send });`;

  it('fails a plain button that sends and is never disabled', () => {
    const text = `function A() { ${save}\n return <button type="button" onClick={() => save.mutate()}>Save</button>; }`;
    expect(unguarded(text)).toEqual(['1 save']);
  });

  it('fails a plain button disabled by isPending, in both checks', () => {
    const text = `function A() { ${save}\n return <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>Save</button>; }`;
    expect(unguarded(text)).toEqual(['1 save']);
    expect(plainBusyButtons(text)).toEqual([2]);
  });

  it('passes a BusyButton with the pending flag as busy', () => {
    const text = `function A() { ${save}\n return <BusyButton type="button" busy={save.isPending} onClick={() => save.mutate()}>Save</BusyButton>; }`;
    expect(unguarded(text)).toEqual([]);
    expect(plainBusyButtons(text)).toEqual([]);
  });

  it('sees generics, destructuring, a submit function and a local busy alias', () => {
    const generic = `const pay = useMutation<Result>({ mutationFn: send });\n<form onSubmit={submit}>\nfunction submit(e) { e.preventDefault(); pay.mutate(); }`;
    expect(unguarded(generic)).toEqual(['1 pay']);
    expect(unguarded(`${generic}\nconst busy = pay.isPending || other;\n<BusyButton busy={busy}>Pay</BusyButton>`)).toEqual([]);
    const destructured = `const { mutate: record, isPending: recording } = useMutation({ mutationFn: send });\n<button onClick={() => record()}>Record</button>`;
    expect(unguarded(destructured)).toEqual(['1 record']);
    expect(unguarded(`${destructured}\n<BusyButton busy={recording}>Record</BusyButton>`)).toEqual([]);
    expect(unguarded(`const { mutate } = useMutation({ mutationFn: send });\n<button onClick={() => mutate()}>Go</button>`)).toEqual(['1 mutate']);
  });

  it('follows a hook that returns a mutation to its callers', () => {
    const hook = `function useSave() {\n  return useMutation({ mutationFn: send });\n}`;
    expect(mutationHooks(hook)).toEqual(['useSave']);
    const caller = `const save = useSave();\n<form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>`;
    expect(unguarded(caller, ['useSave'])).toEqual(['1 save']);
    expect(unguarded(`${caller}\n<SaveBar busy={save.isPending} />`, ['useSave'])).toEqual([]);
  });

  it('honours a busy-exempt comment with a reason directly above the mutation, and nothing else', () => {
    const press = `\n<button onClick={() => save.mutate()}>Save</button>`;
    expect(unguarded(`// busy-exempt: the card shows its own busy state\n${save}${press}`)).toEqual([]);
    expect(unguarded(`  // Saved as the person types.\n  // busy-exempt: the switch says Working itself\n  ${save}${press}`)).toEqual([]);
    expect(unguarded(`// busy-exempt:\n${save}${press}`)).toEqual(['2 save']);
    expect(unguarded(`// busy-exempt: too far up\nconst other = 1;\n${save}${press}`)).toEqual(['3 save']);
    expect(unguarded(`/* busy-exempt: a tag comment */\n${save}${press}`)).toEqual(['2 save']);
  });

  it('leaves a mutation no press sends alone', () => {
    expect(unguarded(`${save}\n<input onChange={(e) => save.mutate(e.target.value)} />`)).toEqual([]);
  });
});

// The success toast guard (#682): every write in the staff area answers back in plain words. A staff mutation passes when its
// options name `meta: { success }` (the app's DoneToast says it), or its own success path shows a success toast: the
// useMutation options (or those of the local hook it comes from), each send with the rest of its block, and the local functions
// and same-file callback props (`onSaved={...}`) these call, three deep, hold a toast call with the 'success' tone or an
// `added(` of useItemAdded. Anything else carries `// toast-exempt: <reason>` in the `//` lines directly above it. Like the
// busy guard it reads text, not data flow, so it proves a success toast is on the path, not what it says.
// JUSTIFICATION-A3: #682 asks for this guard; it reuses the busy guard's mutation parser rather than a second copy.

const TOAST_EXEMPT = /^\/\/\s*toast-exempt:\s*\S/;
const SAYS_DONE = /\bmeta:\s*\{[^}]*\bsuccess\s*:|\b(?:show|\w*[Tt]oast\w*)\??\.?\(\s*[^;]*?['"]success['"]|\badded\(/;

/** The options of each local hook that returns a mutation, by the hook's name. */
function hookOptions(text: string): [string, string][] {
  return [...text.matchAll(/(?:return|=>)\s*useMutation\s*[<(]/g)].flatMap((m) => {
    const name = [...text.slice(0, m.index).matchAll(/(?:function\s+(use\w+)|const\s+(use\w+)\s*=)/g)].at(-1);
    const hook = name?.[1] ?? name?.[2];
    return hook ? [[hook, balanced(text, text.indexOf('(', m.index + m[0].length - 1))] as [string, string]] : [];
  });
}

/** The text from `from` to the end of the block it sits in: a send with what runs after it. */
function restOfBlock(text: string, from: number): string {
  let depth = 0;
  for (let i = from; i < text.length; i += 1) {
    if (text[i] === '{') depth += 1;
    else if (text[i] === '}' && (depth -= 1) < 0) return text.slice(from, i);
  }
  return text.slice(from);
}

/** The code that runs when a mutation works: its options, its hook's, each send to the end of its block, and what they call. */
function successCode(text: string, m: Mutation, hookOpts: Map<string, string>, clean = (part: string) => part): string {
  const callee = /=\s*(\w+)/.exec(text.slice(m.index))?.[1] ?? '';
  const parts = [balanced(text, text.indexOf('(', m.index)), hookOpts.get(callee) ?? ''];
  for (const send of m.sends) {
    for (const call of text.matchAll(new RegExp(`(^|[^\\w.])${send.replace('.', '\\.')}\\s*\\(`, 'g'))) parts.push(restOfBlock(text, call.index + call[0].length - 1));
  }
  return follow(text, parts.map(clean), true);
}

/** The mutations of one file that say nothing when they work and carry no toast-exempt reason. */
function silent(text: string, hooks: string[] = mutationHooks(text), hookOpts = new Map(hookOptions(text))): string[] {
  return mutations(text, hooks)
    .filter((m) => !exempted(text, m.index, TOAST_EXEMPT) && !SAYS_DONE.test(successCode(text, m, hookOpts)))
    .map((m) => `${m.line} ${m.name}`);
}

// One success signal per action (round 2 of #682): a mutation whose success path both shows a success toast and sets a success
// state (`setDone(...)`, `setSaved(true)`, `setMessage('Saved...')`) or renders a success note off its own result
// (`save.isSuccess && <p className="alert alert-success">`, `if (save.data) return <Success>`) says the same thing twice. A
// small note goes and the toast stays; a full success screen stays, says no toast and carries `// toast-exempt: success screen`.
// It checks the DoneToast of `meta.success`; a screen's own `show(text, 'success')` beside its message is the older silent
// toast of rule 15, which the screen hides from assistive technology.
// JUSTIFICATION-A3: round 2 of #682 asks to extend this guard; it reuses successCode and SAYS_DONE above.
const SUCCESS_STATE = /\bset(?:Done|Saved|Message|Outcome|Success|Notice)\(\s*(?!null\b|false\b|undefined\b|''|""|\))/;
const SUCCESS_NOTE = /alert-success|<Success\b|<Waiting\b/;
const DONE_TOAST = /\bmeta:\s*\{[^}]*\bsuccess\s*:/;

/** True when the mutation's component renders a success note off its result: its `isSuccess` or `data`, then a note close by. */
function notesItself(text: string, m: Mutation): boolean {
  const rest = text.slice(m.index);
  const own = rest.slice(0, rest.search(/\n(?:export )?function |$/));
  return [...own.matchAll(new RegExp(`\\b${m.name}\\.(?:isSuccess|data)\\b`, 'g'))].some((use) => SUCCESS_NOTE.test(own.slice(use.index, use.index + 300)));
}

/** `code` without its `onError` handlers, which set the message of a failure, not of a success. */
function withoutErrors(code: string): string {
  const at = code.search(/\bonError\s*:/);
  if (at < 0) return code;
  const line = code.indexOf('\n', at) < 0 ? code.length : code.indexOf('\n', at);
  const brace = code.indexOf('{', at);
  const end = brace >= 0 && brace < line ? brace + balanced(code, brace).length : line;
  return code.slice(0, at) + withoutErrors(code.slice(end));
}

/** The mutations of one file that say they worked twice: a success toast and a success state or note. */
function doubled(text: string, hooks: string[] = mutationHooks(text), hookOpts = new Map(hookOptions(text))): string[] {
  return mutations(text, hooks)
    .filter((m) => {
      const code = successCode(text, m, hookOpts, withoutErrors);
      return DONE_TOAST.test(code) && (SUCCESS_STATE.test(code) || notesItself(text, m));
    })
    .map((m) => `${m.line} ${m.name}`);
}

const hookOpts = new Map(sources.flatMap(({ text }) => hookOptions(text)));
const staff = sources.filter((s) => s.file.startsWith('areas/staff/'));

describe('success toast guard', () => {
  it('finds the staff mutations it guards', () => {
    expect(staff.flatMap(({ text }) => mutations(text, hooks)).length).toBeGreaterThan(150);
  });

  it('every staff mutation says in a toast when it worked, or says why not', () => {
    expect(staff.flatMap(({ file, text }) => silent(text, hooks, hookOpts).map((m) => `${file}:${m}`))).toEqual([]);
  });

  it('no staff mutation says it worked twice, in a toast and in a success note', () => {
    expect(staff.flatMap(({ file, text }) => doubled(text, hooks, hookOpts).map((m) => `${file}:${m}`))).toEqual([]);
  });
});

describe('success toast guard on fixtures', () => {
  const press = `\n<BusyButton busy={save.isPending} onClick={() => save.mutate()}>Save</BusyButton>`;

  it('fails a mutation that says nothing when it works', () => {
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => close() });${press}`)).toEqual(['1 save']);
  });

  it('passes meta.success, a success toast in its options, at its send or after an awaited send', () => {
    expect(silent(`const save = useMutation({ mutationFn: send, meta: { success: 'Item saved' } });${press}`)).toEqual([]);
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => show('Item saved', 'success') });${press}`)).toEqual([]);
    expect(silent(`const save = useMutation({ mutationFn: send });\n<button onClick={() => save.mutate(x, { onSuccess: () => show('Saved', 'success') })} />`)).toEqual([]);
    expect(silent(`const save = useMutation({ mutationFn: send });\nasync function go() { await save.mutateAsync(x); show('Saved', 'success'); }`)).toEqual([]);
  });

  it('never passes an info or error toast as a success', () => {
    expect(silent(`const save = useMutation({ mutationFn: send, onSuccess: () => show('Sent for approval', 'info') });${press}`)).toEqual(['1 save']);
  });

  it('follows a local function and a callback prop of the same file', () => {
    const form = `const save = useMutation({ mutationFn: send, onSuccess: (r) => done(r) });\nfunction done(r) { onSaved(r); }`;
    expect(silent(form)).toEqual(['1 save']);
    expect(silent(`${form}\n<Form onSaved={(r) => show('Expense recorded', 'success')} />`)).toEqual([]);
  });

  it('follows a hook that returns a mutation to its options', () => {
    const hook = `function useSave() {\n  return useMutation({ mutationFn: send, meta: { success: 'Saved' } });\n}`;
    expect(silent(`${hook}\nconst save = useSave();`, ['useSave'])).toEqual([]);
  });

  it('honours a toast-exempt comment with a reason directly above the mutation, and nothing else', () => {
    const save = `const save = useMutation({ mutationFn: send });`;
    expect(silent(`// toast-exempt: the receipt screen that opens says it\n${save}`)).toEqual([]);
    expect(silent(`// toast-exempt:\n${save}`)).toEqual(['2 save']);
    expect(silent(`// busy-exempt: the switch says Working itself\n${save}`)).toEqual(['2 save']);
  });
});
