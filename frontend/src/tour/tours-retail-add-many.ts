import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// The page tour of Add many items (issue #870): rows to type in, a list to paste, one save. Plain words, short sentences, say
// what to tap. tours-retail-add-many.test.tsx renders the page and fails if an anchor is missing.

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);

const retail = (step: Omit<TourStep, 'module'>): TourStep => ({ module: 'retail', ...step });

export const addManyPage: Tour = {
  id: 'page-add-many',
  version: 1,
  title: 'This page: add many items',
  audience: (me) => held(me, 'retail.catalogue.manage') && held(me, 'retail.stock.read'),
  autoStart: false,
  page: '/staff/retail/add-many',
  steps: [
    retail({ id: 'paste', target: 'add-many-paste', title: 'Paste a list', body: 'Copied a list from Excel, Google Sheets or WhatsApp? Tap Paste a list, paste it, then tap Fill the rows.' }),
    retail({ id: 'rows', target: 'add-many-rows', title: 'One item on each row', body: 'Type the name, the prices and how many you have now. Enter takes you to the next box. A row with a problem says what to fix.' }),
    retail({ id: 'save', target: 'add-many-save', title: 'Save them all', body: 'Tap Save the list. Every good row is saved at once. Rows that need a look stay here.' }),
  ],
};

export const RETAIL_ADD_MANY_TOURS: readonly Tour[] = [addManyPage];
