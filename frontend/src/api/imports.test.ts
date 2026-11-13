import { beforeEach, describe, expect, it, vi } from 'vitest';

// The refusal of a large batch while another business's large batch is written (issue #900): the trial run, the start and the
// resume of an import answer import_busy (409) in plain words, whatever the server's detail says. Fabricated data only.

const post = vi.hoisted(() => vi.fn());
vi.mock('./client', () => ({ api: { POST: post, GET: vi.fn() }, problemOf: (error: unknown) => error ?? {} }));

import { IMPORT_BUSY_MESSAGE, ImportError, platformAction, tryImport } from './imports';

const busy = { code: 'import_busy', status: 409, title: 'Import busy', detail: "Another business's large import is being written." };

beforeEach(() => {
  post.mockReset();
  post.mockResolvedValue({ error: busy });
});

describe('import_busy', () => {
  it('answers the trial run in plain words', async () => {
    await expect(tryImport('run-1')).rejects.toThrow(IMPORT_BUSY_MESSAGE);
    await expect(tryImport('run-1')).rejects.toBeInstanceOf(ImportError);
  });

  it.each(['start', 'resume'] as const)('answers the %s of an import in plain words, with its code', async (action) => {
    const refused = await platformAction('test-shop-one', 'run-1', action).catch((e: unknown) => e);
    expect(refused).toBeInstanceOf(ImportError);
    expect((refused as ImportError).message).toBe('Another large import is running, try again in a few minutes.');
    expect((refused as ImportError).code).toBe('import_busy');
  });

  it('leaves every other refusal in the server words', async () => {
    post.mockResolvedValue({ error: { code: 'IMPORT_IN_PROGRESS', detail: 'Another import of this business is being committed.' } });
    await expect(tryImport('run-1')).rejects.toThrow('Another import of this business is being committed.');
  });
});
