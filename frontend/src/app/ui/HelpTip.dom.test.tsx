// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { HELP_TIPS } from '../help-tips';
import { HelpTip } from './HelpTip';

// The help tip (issue #707): it opens on a tap and on keyboard focus, never on hover only, closes on a tap outside or Escape,
// and a screen reader hears its name and its words.

afterEach(cleanup);

const WORDS = HELP_TIPS.onCredit.text;

function page() {
  render(
    <>
      <p>On credit <HelpTip tip="onCredit" /></p>
      <button type="button">Elsewhere</button>
    </>,
  );
  return screen.getByRole('button', { name: 'More about On credit' });
}

describe('the help tip', () => {
  it('is a button named after its label, closed at first', () => {
    const tip = page();
    expect(tip.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText(WORDS)).toBeNull();
  });

  it('opens on a tap, in a live region the button is described by, and a second tap closes it', () => {
    const tip = page();
    fireEvent.pointerDown(tip);
    fireEvent.focus(tip);
    fireEvent.click(tip);
    expect(tip.getAttribute('aria-expanded')).toBe('true');
    const words = screen.getByRole('status');
    expect(words.textContent).toBe(WORDS);
    expect(tip.getAttribute('aria-describedby')).toBe(words.id);
    fireEvent.click(tip);
    expect(tip.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText(WORDS)).toBeNull();
  });

  it('opens on keyboard focus and closes on Escape', () => {
    const tip = page();
    fireEvent.focus(tip);
    expect(screen.getByText(WORDS)).toBeTruthy();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(tip.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText(WORDS)).toBeNull();
  });

  it('closes on a tap anywhere else, and stays open on a tap on its own words', () => {
    const tip = page();
    fireEvent.click(tip);
    fireEvent.pointerDown(screen.getByText(WORDS));
    expect(screen.getByText(WORDS)).toBeTruthy();
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Elsewhere' }));
    expect(screen.queryByText(WORDS)).toBeNull();
  });

  // JUSTIFICATION-A3: one test per finding of the #712 review (scroll, blur, live region, Enter after focus).
  it('stays open when the page or a table scrolls, as the browser does to show a focused header', () => {
    const tip = page();
    fireEvent.focus(tip);
    fireEvent.scroll(window);
    expect(screen.getByText(WORDS)).toBeTruthy();
  });

  it('closes when focus moves away, so two tips never stay open together', () => {
    const tip = page();
    fireEvent.focus(tip);
    fireEvent.blur(tip, { relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }) });
    expect(tip.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText(WORDS)).toBeNull();
  });

  it('keeps its live region in the page while closed, so the words are announced as they appear', () => {
    const tip = page();
    const region = screen.getByRole('status');
    expect(region.textContent).toBe('');
    expect(tip.getAttribute('aria-describedby')).toBe(region.id);
    fireEvent.click(tip);
    expect(screen.getByRole('status')).toBe(region);
    expect(region.textContent).toBe(WORDS);
  });

  it('keeps a tip opened by keyboard focus open on Enter', () => {
    const tip = page();
    fireEvent.focus(tip);
    fireEvent.click(tip);
    expect(screen.getByText(WORDS)).toBeTruthy();
  });

  it('keeps every tip short and plain: one or two sentences, no dashes', () => {
    for (const { label, text } of Object.values(HELP_TIPS)) {
      expect(text.split(/[.?!](\s|$)/).filter((s) => s.trim() !== '').length, label).toBeLessThanOrEqual(2);
      expect(text, label).not.toMatch(/[‒-―-]/);
    }
  });
});
