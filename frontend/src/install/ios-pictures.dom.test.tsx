// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import { IosSteps } from './InstallPrompt';
import { addButton, addToHomeScreen, safariTab, shareButton } from './ios-pictures';

// The pictures beside the iPhone install steps (issue #448): one drawn image per step, labelled with the step, small, and
// painted from the theme tokens only.

afterEach(cleanup);

describe('the iPhone install steps with pictures', () => {
  it('renders four images, one beside each step, each labelled with its step', () => {
    render(<IosSteps />);
    const steps = within(screen.getByTestId('ios-install-steps')).getAllByRole('listitem');
    expect(steps).toHaveLength(4);
    const images = screen.getAllByRole('img');
    expect(images).toHaveLength(4);
    const labels = images.map((img) => img.getAttribute('aria-label') ?? '');
    expect(labels[0]).toContain('Safari');
    expect(labels[0]).toContain('Private');
    expect(labels[1]).toContain('Share');
    expect(labels[2]).toContain('Add to Home Screen');
    expect(labels[2]).toContain('View More');
    expect(labels[3]).toContain('Add');
    steps.forEach((step, i) => expect(within(step).getByRole('img')).toBe(images[i]));
  });

  it('keeps the words of every step', () => {
    render(<IosSteps />);
    const text = screen.getByTestId('ios-install-steps').textContent ?? '';
    for (const words of ['normal tab', 'square with an arrow', 'tap the address bar', 'scroll down, or tap View More', 'Then open BMS']) {
      expect(text).toContain(words);
    }
  });

  it('keeps each picture under 2 KB and free of fixed colours', () => {
    for (const picture of [safariTab, shareButton, addToHomeScreen, addButton]) {
      const markup = renderToStaticMarkup(picture);
      expect(markup.length).toBeLessThan(2048);
      expect(markup).not.toMatch(/fill="#|stroke="#|style=|<image|href=/);
    }
  });
});
