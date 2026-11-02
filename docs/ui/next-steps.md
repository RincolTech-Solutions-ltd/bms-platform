# Next steps (issue #700)

The owner, 29 Oct 2026: "We wanted to count stock at the same place where he adds items... I told you to have all these
inner links well visible to enable easy routing around the app." So every staff retail screen listed here shows its next
job as a short **Next** row of plain, full size buttons (48px targets, a full width stack on a phone), never as a small link
or an underlined word. The first button is the primary one only where the table says so, so a screen keeps one primary
action (docs/ui-rules.md rule 6).

This file is the single source of the list. `frontend/src/areas/staff/retail/next-steps.tsx` holds the same rows in code
(`NEXT_STEPS`), and `next-steps.dom.test.tsx` fails when the two differ, then draws each screen and looks for its buttons.
A new next step is added here first, then in `NEXT_STEPS`, then on its screen.

Each button shows only to someone who may use it: the screen it opens is checked with `canUse`, and a step done at a branch
(count, sell, move) also needs that permission at that branch. A row whose buttons are all hidden is not drawn.

| Key | Screen | Shown when | Next buttons, in order | Primary |
| --- | --- | --- | --- | --- |
| `items` | Items | always, under the search | Count stock; Buy stock | none |
| `itemAdded` | Items and a category, after Add an item | the item is saved | Count it now; Add another; Sell it | Count it now |
| `quickAdded` | Quick add | the item is added | Count it now; Add another; Sell it | Count it now, else Add another |
| `countFromAdd` | Count stock | opened from Count it now | Back to Items | none |
| `countDone` | Count stock | the count is committed | Start another count; Back to My stock; Count another branch | Start another count |
| `buyDone` | Buy stock | the purchase is saved | Buy more stock; Move to the shop; My stock | Buy more stock |
| `moveDone` | Move stock | the stock is moved or received | Move more stock; Count stock at the receiving branch | Move more stock |

Where a screen only shows a past purchase or move (Stock moves opens one), Buy more stock or Move more stock is still the
primary and opens Buy stock or Move stock. Count stock done draws its buttons in the success screen (`SuccessScreen`), one
under another.

## What each button does

- **Count it now** opens Count stock at the branch in use (on Quick add, at the branch the item was added at) with the new
  item already on the sheet and its box focused, ready to type how many. It shows only where the person may count at that
  branch and it keeps goods. Quick add shows it only when nothing was counted (no quantity, or 0), since a quantity is the count;
  its count has no Back to Items, since Quick add is often reached from Sell. A help tip beside it says what it does. A switch
  to another branch on Count stock starts a plain count there, without the item.
- **Add another** opens a clear Add an item form (on Quick add, keeping the category and where).
- **Sell it** opens Record a sale with the item on it, where the person may sell at that branch and it sells goods.
- **Back to Items** returns to the Items list from a count opened by Count it now. It sits next to Review the difference.
- **Count another branch** shows the other branches that keep goods where the person may count; one tap switches to it.
- **Move to the shop** opens Move stock from the branch the purchase went into. It shows only when that branch is a store
  (it keeps goods but does not sell), since a shop that buys its own stock has nothing to move.
- **Count stock at the receiving branch** names the branch, such as "Count stock at Test Shop", and switches to it. It
  shows once the goods are there (moved, or received on a two step move), not while they are on the way.

## The guard

`next-steps.dom.test.tsx` also scans every screen for the one success screen (`SuccessScreen`) and fails when its list of
next steps is empty, or is anything but a list that opens with a step or a spread of steps; it checks itself on an empty list
first. The other done screens of this list are each drawn by the test with their buttons.
