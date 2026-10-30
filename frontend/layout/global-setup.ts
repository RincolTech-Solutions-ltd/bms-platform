import react from '@vitejs/plugin-react';
import { createLogger, createServer } from 'vite';
import type { TestProject } from 'vitest/node';
import { helpCorpus } from '../vite.config';

// One Vite server for every layout test (issue #717). Each test file used to start its own with no app plugins and the default
// dependency cache: the scan then crawled index.html into src/main.tsx, could not resolve virtual:pwa-register or
// virtual:help-corpus, and skipped pre-bundling, so Vite found the dependencies while a page loaded, optimized them again
// mid-load, answered 504 Outdated Optimize Dep and left the page blank, while up to six servers rewrote one node_modules/.vite.
// Here the scan reads only the layout pages (never index.html, so never the service worker's virtual:pwa-register), the help
// corpus resolves through the app's own plugin, and the cache is the layout suite's own. A scan that fails stops the run before
// any test; an optimization during the run reloads the open pages, which openLayout fails at once, and is named after the run.

declare module 'vitest' {
  export interface ProvidedContext {
    layoutBase: string;
  }
}

export default async function setup(project: TestProject) {
  const logger = createLogger('info');
  const problems: string[] = [];
  logger.info = (msg) => {
    if (/new dependencies optimized|optimized dependencies changed/.test(msg)) problems.push(msg);
  };
  const error = logger.error;
  logger.error = (msg, options) => {
    problems.push(msg);
    error(msg, options);
  };
  const server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: 'node_modules/.vite-layout',
    plugins: [react(), helpCorpus()],
    customLogger: logger,
    optimizeDeps: { entries: ['layout/*.html'] },
    server: { port: 0, host: '127.0.0.1' },
  });
  await server.listen();
  // A scan that fails is the cause of #717 even on a run the pages survive, so it stops the run before any test.
  await server.environments.client.depsOptimizer?.scanProcessing;
  if (problems.length > 0) {
    await server.close();
    throw new Error(`The layout server could not scan the layout pages' dependencies (issue #717):\n${problems.join('\n')}`);
  }
  project.provide('layoutBase', server.resolvedUrls?.local[0] ?? '');
  return async () => {
    await server.close();
    if (problems.length > 0) throw new Error(`The layout server optimized dependencies again or failed during the run (issue #717):\n${problems.join('\n')}`);
  };
}
