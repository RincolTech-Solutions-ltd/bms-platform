import { createMemoryHistory, createRootRoute, createRouter } from '@tanstack/react-router';

// The router a layout page gives its links and tabs (issue #717): a root route alone at `path`, so the page never loads the app's
// route tree and every screen behind it. The pages render inside RouterContextProvider, so no route of this router renders.

/** A router that stands at `path`, for the links and the current location of a layout page. */
export function layoutRouter(path: string) {
  return createRouter({ routeTree: createRootRoute(), history: createMemoryHistory({ initialEntries: [path] }) });
}
