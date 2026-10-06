import type { Me } from '../api/client';
import type { Tour, TourStep } from './model';

// The tours themselves (issue #19). Copy rules: plain English, short sentences, no jargon, say
// what to tap. Every target is a data-tour attribute on a screen; tours.test.tsx renders each
// screen and fails if a target is missing, so renaming a screen means updating its tour here.
// A step for a feature that is not built yet carries `feature` and stays hidden until the slice
// that builds it switches its flag on in model.ts and bumps the tour version.

const SETTINGS = 'core.settings.manage';
const SELL = 'retail.sale.create';
const RESTOCK = 'retail.purchase.create';

const held = (me: Me, permission: string) => (me.permissions ?? []).includes(permission);
const isTenantAdmin = (me: Me) => held(me, SETTINGS);
const isSeller = (me: Me) => held(me, SELL) && !isTenantAdmin(me);

const help: TourStep = {
  id: 'help',
  target: 'help-menu',
  title: 'Help is always here',
  body: 'Lost? Tap Help to take this tour again, or to see a short tour of the page you are on.',
};

const branch: TourStep = {
  id: 'branch',
  target: 'branch-picker',
  title: 'Your branch',
  body: 'Pick the branch you are working in. What you see and record follows this choice.',
};

export const adminWelcome: Tour = {
  id: 'admin-welcome',
  version: 1,
  title: 'Welcome tour',
  audience: isTenantAdmin,
  autoStart: true,
  steps: [
    {
      id: 'welcome',
      title: 'Welcome to your business account',
      body: 'This short tour shows you where things are. It takes about two minutes. You can stop at any time.',
    },
    {
      id: 'checklist',
      route: '/staff/setup',
      target: 'setup-checklist',
      optional: true,
      title: 'Your set-up checklist',
      body: 'This list shows what is left to set up. Work through it at your own pace.',
    },
    {
      id: 'logo',
      route: '/staff/setup',
      target: 'setup-logo',
      title: 'Add your logo',
      body: 'Upload your logo here. You and your staff will see it at the top of every page.',
    },
    {
      id: 'colour',
      route: '/staff/setup',
      target: 'setup-colour',
      title: 'Pick your colour',
      body: 'Choose the colour of your business. We check that text stays easy to read on it.',
    },
    {
      id: 'staff',
      route: '/staff/users',
      target: 'staff-invite',
      feature: 'staffAdmin',
      title: 'Add your staff',
      body: 'Invite each person by email or phone. Give them a role, like Sales or Cashier. They only see what their role allows.',
    },
    {
      id: 'modules',
      target: 'nav-areas',
      title: 'Your modules',
      body: 'Each part of the business you use has a tab here, like Retail or Lending. To add or remove one, contact us.',
    },
    {
      id: 'billing',
      route: '/staff/billing',
      target: 'billing-status',
      feature: 'billing',
      title: 'Your plan',
      body: 'See your plan, your modules and when the next payment is due.',
    },
    {
      id: 'trial',
      route: '/staff/billing',
      target: 'billing-trial',
      feature: 'trial',
      title: 'Your free month',
      body: 'See how many free days are left. Your data stays safe when the month ends.',
    },
    {
      id: 'pay',
      route: '/staff/billing',
      target: 'billing-paid',
      feature: 'payments',
      title: 'Tell us you have paid',
      body: 'Pay by mobile money or bank. Then tap I have paid and enter the reference.',
    },
    {
      id: 'agent',
      route: '/staff/billing',
      target: 'billing-agent',
      feature: 'agents',
      title: 'Your agent',
      body: 'If an agent helped you join, you can see who to call here.',
    },
    { ...branch, route: '/staff' },
    {
      id: 'retail',
      route: '/staff/retail',
      target: 'retail-tiles',
      module: 'retail',
      title: 'Your shop each day',
      body: 'Record sales, check stock and restock from here. One big button for each job.',
    },
    {
      id: 'lending',
      route: '/staff',
      target: 'lending-members',
      module: 'lending',
      needs: ['lending.members.read'],
      title: 'Your members',
      body: 'Your members are listed here, for the branch you picked.',
    },
    {
      id: 'approvals',
      target: 'nav-approvals',
      needs: ['core.approvals.read'],
      optional: true,
      title: 'Approvals',
      body: 'Some actions need a second person to agree. They wait for you here.',
    },
    help,
  ],
};

export const retailSales: Tour = {
  id: 'retail-sales',
  version: 1,
  title: 'Selling tour',
  audience: isSeller,
  autoStart: true,
  steps: [
    {
      id: 'welcome',
      title: 'Welcome',
      body: 'Here is how to sell, check stock and restock. It takes about a minute.',
    },
    { ...branch, optional: true, body: 'Sales and stock are counted per branch. Check that this shows the shop you are in.' },
    {
      id: 'sale',
      route: '/staff/retail',
      target: 'retail-sale',
      module: 'retail',
      title: 'Record a sale',
      body: 'Tap here each time you sell. Add the items, choose how the customer pays, and save.',
    },
    {
      id: 'sale-search',
      route: '/staff/retail/sale',
      target: 'sale-search',
      module: 'retail',
      optional: true,
      title: 'Find the item',
      body: 'Type part of the name or code, then tap Add.',
    },
    {
      id: 'sale-payment',
      route: '/staff/retail/sale',
      target: 'sale-payment',
      module: 'retail',
      optional: true,
      title: 'How the customer pays',
      body: 'Choose how the customer pays. For credit, pick the buyer and the day they will pay.',
    },
    {
      id: 'sale-save',
      route: '/staff/retail/sale',
      target: 'sale-save',
      module: 'retail',
      optional: true,
      title: 'Save the sale',
      body: 'Check the total, then tap Save sale. You get a receipt to show the customer.',
    },
    {
      id: 'stock',
      route: '/staff/retail',
      target: 'retail-stock',
      module: 'retail',
      needs: ['retail.stock.read'],
      title: 'Check stock',
      body: 'See how many of each item your branch has.',
    },
    {
      id: 'stock-search',
      route: '/staff/retail/stock',
      target: 'stock-search',
      module: 'retail',
      needs: ['retail.stock.read'],
      optional: true,
      title: 'Search the stock',
      body: 'Type a name or code. An item marked negative needs a check, so tell your manager.',
    },
    {
      id: 'restock',
      route: '/staff/retail',
      target: 'retail-restock',
      module: 'retail',
      needs: [RESTOCK],
      title: 'Restock',
      body: 'When new stock comes from a supplier, record it here. The counts stay right.',
    },
    {
      id: 'restock-ask',
      route: '/staff/retail/stock',
      target: 'stock-search',
      module: 'retail',
      unless: [RESTOCK],
      optional: true,
      title: 'Running low?',
      body: 'Tell your manager when an item runs low. They record new stock when it arrives.',
    },
    {
      id: 'shortcuts',
      route: '/staff/retail',
      target: 'retail-nav',
      module: 'retail',
      optional: true,
      title: 'Shortcuts',
      body: 'On a phone, your most used pages are always at the bottom of the screen.',
    },
    help,
  ],
};

export const staffWelcome: Tour = {
  id: 'staff-welcome',
  version: 1,
  title: 'Welcome tour',
  audience: (me) => !isTenantAdmin(me) && !isSeller(me),
  autoStart: true,
  steps: [
    {
      id: 'welcome',
      title: 'Welcome',
      body: 'This short tour shows you the pages you can use. You can stop at any time.',
    },
    { ...branch, optional: true },
    {
      id: 'members',
      route: '/staff',
      target: 'lending-members',
      module: 'lending',
      needs: ['lending.members.read'],
      title: 'Members',
      body: 'Your members are listed here, for the branch you picked.',
    },
    {
      id: 'approvals',
      target: 'nav-approvals',
      needs: ['core.approvals.read'],
      optional: true,
      title: 'Approvals',
      body: 'Some actions need a second person to agree. They wait for you here.',
    },
    {
      id: 'retail',
      target: 'nav-retail',
      module: 'retail',
      optional: true,
      title: 'Retail',
      body: 'Sales and stock live here.',
    },
    help,
  ],
};

export const salePage: Tour = {
  id: 'page-sale',
  version: 1,
  title: 'This page: record a sale',
  audience: (me) => held(me, SELL),
  autoStart: false,
  page: '/staff/retail/sale',
  steps: retailSales.steps.filter((s) => s.route === '/staff/retail/sale'),
};

export const stockPage: Tour = {
  id: 'page-stock',
  version: 1,
  title: 'This page: stock',
  audience: (me) => held(me, 'retail.stock.read'),
  autoStart: false,
  page: '/staff/retail/stock',
  steps: retailSales.steps.filter((s) => s.id === 'stock-search' || s.id === 'restock-ask'),
};

export const setupPage: Tour = {
  id: 'page-setup',
  version: 1,
  title: 'This page: business set-up',
  audience: isTenantAdmin,
  autoStart: false,
  page: '/staff/setup',
  steps: [
    ...adminWelcome.steps.filter((s) => s.route === '/staff/setup'),
    {
      id: 'name',
      target: 'setup-name',
      title: 'Your business name',
      body: 'This name shows at the top of every page. Type it as your customers know it.',
    },
    {
      id: 'save',
      target: 'setup-save',
      title: 'Save',
      body: 'Tap Save when you are happy. The logo saves as soon as you upload it.',
    },
  ],
};

/** Every tour, in the order the first matching auto tour is chosen. */
export const TOURS: readonly Tour[] = [adminWelcome, retailSales, staffWelcome, salePage, stockPage, setupPage];
