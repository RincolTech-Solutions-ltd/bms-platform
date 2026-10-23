// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SKELETON_DELAY, Skeleton } from './Skeleton';

// The one loading state (#520): nothing for a second, then rows, and never over content already shown.

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const rows = () => document.querySelectorAll('.skeleton-row').length;

describe('Skeleton', () => {
  it('shows nothing at all for the first second of a load', () => {
    render(<Skeleton loading rows={4}>Content</Skeleton>);
    expect(document.body.textContent).toBe('');
    act(() => vi.advanceTimersByTime(SKELETON_DELAY - 1));
    expect(rows()).toBe(0);
  });

  it('then shows the rows, hidden from a screen reader behind one Loading', () => {
    render(<Skeleton loading rows={4}>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    expect(rows()).toBe(4);
    expect(screen.getByRole('status').getAttribute('aria-busy')).toBe('true');
    expect(screen.getByRole('status').textContent).toBe('Loading');
    expect(document.querySelector('.skeleton-row')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('shows the content once loaded', () => {
    const { rerender } = render(<Skeleton loading>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    rerender(<Skeleton loading={false}>Content</Skeleton>);
    expect(rows()).toBe(0);
    expect(screen.getByText('Content')).toBeTruthy();
  });

  it('never covers content already on the screen with a later load', () => {
    const { rerender } = render(<Skeleton loading={false}>Content</Skeleton>);
    rerender(<Skeleton loading>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY * 3));
    expect(rows()).toBe(0);
    expect(screen.getByText('Content')).toBeTruthy();
  });

  it('shows nothing when the load ends inside the second', () => {
    const { rerender } = render(<Skeleton loading>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(400));
    rerender(<Skeleton loading={false}>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    expect(rows()).toBe(0);
    expect(screen.getByText('Content')).toBeTruthy();
  });
});
