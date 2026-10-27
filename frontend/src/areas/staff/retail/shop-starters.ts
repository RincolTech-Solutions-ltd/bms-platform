// The starting sets of Set up your shop (issue #625, docs/specs/units-packs-and-costing.md section 4 and section 9 step 5): for
// each kind of shop, the categories, the units things are counted in, and the packs with their usual size and what they are for.
// They are bundled with the app, so the list shows with no network and no AI. Names are the words a shop in Kampala uses, and the
// units start from the eight every retail shop already has (ADR-098: Piece, Pair, Set, Box, Pack, Kg, Litre, Metre), so a name
// that is there already is skipped. A pack never carries a price here: the owner sets prices on each item (#624).

export type PackUse = 'buying' | 'selling' | 'both';

export interface StarterPack {
  name: string;
  /** How many of `of` are in one pack: a starting number the owner may change. */
  size: number;
  /** The smaller unit the size counts, one of the shop type's units or one the shop has. */
  of: string;
  use: PackUse;
}

export interface ShopType {
  id: string;
  label: string;
  /** What the shop sells, in a few words, under its name. */
  hint: string;
  categories: string[];
  units: string[];
  packs: StarterPack[];
}

/** The common units, for every kind of shop and the whole set of Something else. */
const COMMON = ['Piece', 'Pair', 'Set', 'Kg', 'Litre', 'Metre'];

export const SHOP_TYPES: ShopType[] = [
  {
    id: 'bedding',
    label: 'Bedding and home',
    hint: 'Bedsheets, blankets, curtains, kitchen things',
    categories: ['Bedsheets', 'Blankets', 'Duvets', 'Pillows', 'Curtains', 'Towels', 'Mattresses', 'Kitchenware'],
    units: ['Piece', 'Pair', 'Set', 'Metre'],
    packs: [
      { name: 'Bale', size: 50, of: 'Piece', use: 'buying' },
      { name: 'Dozen', size: 12, of: 'Piece', use: 'both' },
      { name: 'Roll', size: 50, of: 'Metre', use: 'both' },
    ],
  },
  {
    id: 'electrical',
    label: 'Electrical and electronics',
    hint: 'Bulbs, sockets, cables, TVs, radios',
    categories: ['Bulbs', 'Sockets and switches', 'Extensions', 'Cables', 'TVs', 'Radios and speakers', 'Batteries'],
    units: ['Piece', 'Metre'],
    packs: [
      { name: 'Carton', size: 100, of: 'Piece', use: 'buying' },
      { name: 'Box', size: 10, of: 'Piece', use: 'both' },
      { name: 'Dozen', size: 12, of: 'Piece', use: 'selling' },
      { name: 'Roll', size: 100, of: 'Metre', use: 'both' },
    ],
  },
  {
    id: 'hardware',
    label: 'Hardware and building',
    hint: 'Nails, cement, paint, pipes, tools',
    categories: ['Nails and screws', 'Cement', 'Paint', 'Pipes and fittings', 'Tools', 'Iron sheets', 'Wire'],
    units: ['Piece', 'Kg', 'Metre', 'Litre'],
    packs: [
      { name: 'Box', size: 50, of: 'Piece', use: 'both' },
      { name: 'Roll', size: 100, of: 'Metre', use: 'both' },
      { name: 'Dozen', size: 12, of: 'Piece', use: 'selling' },
      { name: 'Tin', size: 4, of: 'Litre', use: 'both' },
    ],
  },
  {
    id: 'phones',
    label: 'Phones and accessories',
    hint: 'Phones, chargers, earphones, covers',
    categories: ['Phones', 'Chargers', 'Earphones', 'Phone covers', 'Screen protectors', 'Memory cards'],
    units: ['Piece'],
    packs: [
      { name: 'Box', size: 10, of: 'Piece', use: 'both' },
      { name: 'Carton', size: 100, of: 'Piece', use: 'buying' },
    ],
  },
  {
    id: 'clothing',
    label: 'Clothing and shoes',
    hint: 'Shirts, dresses, shoes, bags',
    categories: ['Shirts', 'Trousers', 'Dresses', 'Shoes', 'Children clothes', 'Underwear', 'Bags'],
    units: ['Piece', 'Pair', 'Set'],
    packs: [
      { name: 'Bale', size: 100, of: 'Piece', use: 'buying' },
      { name: 'Dozen', size: 12, of: 'Piece', use: 'both' },
    ],
  },
  {
    id: 'supermarket',
    label: 'Supermarket and general',
    hint: 'Drinks, sugar, rice, soap, eggs',
    categories: ['Drinks', 'Sugar, rice and flour', 'Cooking oil', 'Soap and cleaning', 'Biscuits and sweets', 'Eggs', 'Toiletries'],
    units: ['Piece', 'Kg', 'Litre', 'Bottle', 'Packet'],
    packs: [
      { name: 'Crate', size: 24, of: 'Bottle', use: 'both' },
      { name: 'Carton', size: 24, of: 'Piece', use: 'both' },
      { name: 'Half carton', size: 12, of: 'Piece', use: 'selling' },
      { name: 'Sack', size: 50, of: 'Kg', use: 'both' },
      { name: 'Jerrycan', size: 20, of: 'Litre', use: 'both' },
      { name: 'Tray', size: 30, of: 'Piece', use: 'both' },
    ],
  },
  {
    id: 'pharmacy',
    label: 'Pharmacy and cosmetics',
    hint: 'Medicines, first aid, skin and hair care',
    categories: ['Medicines', 'First aid', 'Baby care', 'Skin care', 'Hair care', 'Perfumes'],
    units: ['Piece', 'Tablet', 'Bottle', 'Tube'],
    packs: [
      { name: 'Strip', size: 10, of: 'Tablet', use: 'both' },
      { name: 'Tin', size: 1000, of: 'Tablet', use: 'buying' },
      { name: 'Carton', size: 24, of: 'Piece', use: 'buying' },
      { name: 'Dozen', size: 12, of: 'Piece', use: 'both' },
    ],
  },
  {
    id: 'other',
    label: 'Something else',
    hint: 'Just the common units',
    categories: [],
    units: COMMON,
    packs: [],
  },
];

export function shopTypeById(id: string): ShopType | undefined {
  return SHOP_TYPES.find((t) => t.id === id);
}
