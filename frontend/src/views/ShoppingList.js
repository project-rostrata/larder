import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { api } from "../api.js";
import { applyItemChange } from "../shoppingLists.js";
import { TopBar } from "../components/TopBar.js";
import { Footer } from "../components/Footer.js";
import { DialogHost } from "../components/DialogHost.js";
import { navigate } from "../router.js";
import { CheckIcon, PlusIcon, GripIcon, MoreIcon } from "../icons.js";
import { setPantryFromList } from "../pantry.js";
import { emptyNode } from "../vanHelpers.js";

const { div, h1, h2, p, span, button, form, input, label } = van.tags;

const rowOf = (id) => document.querySelector(`.shop-item[data-id="${id}"]`);

// After a move the list re-renders with new row elements; put keyboard focus back on the moved
// item's handle once VanJS has applied the update.
function refocusGrip(id) {
  requestAnimationFrame(() => rowOf(id)?.querySelector(".shop-grip")?.focus());
}

// Pointer Events rather than HTML5 drag-and-drop, which doesn't work on touch screens. Only the
// grip starts a drag (touch-action: none on it), so the rest of the row still scrolls normally.
// The dragged row follows the pointer; a line marks where it'll land; on release the API is
// told "put this before that item" and returns the reordered list.
function startDrag(e, list, item) {
  if (e.pointerType === "mouse" && e.button !== 0) return;
  e.preventDefault();
  const handle = e.currentTarget;
  const row = handle.closest(".shop-item");
  const others = [...row.parentElement.querySelectorAll(".shop-item")].filter((r) => r !== row);
  const originalNext = row.nextElementSibling?.dataset.id ?? null;
  const startY = e.clientY;
  let before; // undefined until the pointer actually moves
  handle.setPointerCapture(e.pointerId);
  row.classList.add("dragging");

  const clearMarks = () => others.forEach((r) => r.classList.remove("drop-before", "drop-after"));
  // The first row whose midpoint is below y, or undefined for "after the last row".
  const targetAt = (y) => others.find((r) => {
    const box = r.getBoundingClientRect();
    return y < box.top + box.height / 2;
  });

  function onMove(ev) {
    row.style.transform = `translateY(${ev.clientY - startY}px)`;
    const target = targetAt(ev.clientY);
    clearMarks();
    if (target) target.classList.add("drop-before");
    else others.at(-1)?.classList.add("drop-after");
    before = target ? target.dataset.id : null;
  }

  function onEnd(ev) {
    handle.removeEventListener("pointermove", onMove);
    handle.removeEventListener("pointerup", onEnd);
    handle.removeEventListener("pointercancel", onEnd);
    row.classList.remove("dragging");
    row.style.transform = "";
    clearMarks();
    if (ev.type === "pointercancel" || before === undefined) return;
    // Drop where the pointer is released, not wherever the last move event happened to land.
    const target = targetAt(ev.clientY);
    before = target ? target.dataset.id : null;
    if (before === originalNext) return;
    applyItemChange(api.moveShoppingItem(list.id, item.id, before));
  }

  handle.addEventListener("pointermove", onMove);
  handle.addEventListener("pointerup", onEnd);
  handle.addEventListener("pointercancel", onEnd);
}

// Arrow keys on the grip move the item one place up or down.
async function keyMove(e, list, item) {
  if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
  e.preventDefault();
  const row = rowOf(item.id);
  let before;
  if (e.key === "ArrowUp") {
    const prev = row.previousElementSibling;
    if (!prev) return;
    before = prev.dataset.id;
  } else {
    const next = row.nextElementSibling;
    if (!next) return;
    before = next.nextElementSibling?.dataset.id ?? null;
  }
  if (await applyItemChange(api.moveShoppingItem(list.id, item.id, before))) refocusGrip(item.id);
}

// One row per contributing recipe, amount first and lightly emphasized. Each row stays on one
// line: a recipe title too long for the card is cut off with an ellipsis, and the full line
// shows on hover.
function SourceLines(item) {
  if (!item.sourceLines.length) return null;
  return div(
    { class: "shop-item-sources" },
    item.sourceLines.map((line) =>
      div(
        { class: "shop-source", title: line.display },
        span({ class: "shop-source-amount" }, line.amountDisplay),
        ` — ${line.recipeTitle}`,
      )),
  );
}

// Name first (what you scan for in the store), amount right-aligned at the same size and
// weight -- the layout borrowed from Mealime's list. Both parts come ready-made from the API.
function ItemText(item) {
  return span(
    { class: "shop-item-text" },
    span({ class: "shop-name" }, item.nameDisplay),
    item.amountParts.length
      ? span(
        { class: "shop-amount" },
        ...item.amountParts.flatMap((part, i) => [i ? " + " : null, span({ class: "shop-amount-part" }, part)]),
      )
      : null,
  );
}

// A row's ⋯ menu: remove from the list, and add to / remove from the pantry (parsed ingredients
// only). Its own binding, so opening a menu doesn't re-render the list.
function RowMenu(list, item) {
  const act = (fn) => () => { state.openMenuId.val = null; fn(); };
  const entries = [
    button({ role: "menuitem", class: "danger", onclick: act(() => applyItemChange(api.deleteShoppingItem(list.id, item.id))) }, "Remove from list"),
  ];
  if (item.ingredientId) {
    entries.unshift(item.inPantry
      ? button({ role: "menuitem", onclick: act(() => setPantryFromList(item, false)) }, "Remove from pantry")
      : button({ role: "menuitem", onclick: act(() => setPantryFromList(item, true)) }, "Add to pantry"));
  }
  return div(
    { class: "shop-menu-wrap" },
    button(
      {
        class: "icon-btn shop-menu-button",
        "aria-label": `Actions for ${item.display}`,
        "aria-haspopup": "menu",
        "aria-expanded": () => String(state.openMenuId.val === item.id),
        onclick: () => {
          state.openMenuId.val = state.openMenuId.val === item.id ? null : item.id;
          if (state.openMenuId.val) requestAnimationFrame(() => document.querySelector(".shop-menu [role=menuitem]")?.focus());
        },
      },
      MoreIcon({ size: 20, strokeWidth: 3 }),
    ),
    () => (state.openMenuId.val === item.id ? div({ class: "shop-menu", role: "menu" }, ...entries) : emptyNode()),
  );
}

// Merge-mode state lives with the view: entering the view (a fresh mount) always starts in
// normal check-off mode.
export function ShoppingList() {
  const selecting = van.state(false);
  const selected = van.state([]); // item ids in pick order; the first keeps its name
  const newItem = van.state("");
  const remember = van.state(true);

  function exitSelecting() {
    selecting.val = false;
    selected.val = [];
    remember.val = true;
  }

  function toggleSelected(id) {
    selected.val = selected.val.includes(id) ? selected.val.filter((x) => x !== id) : [...selected.val, id];
  }

  async function mergeSelected() {
    const list = state.currentShoppingList.val;
    if (await applyItemChange(api.mergeShoppingItems(list.id, selected.val, remember.val))) exitSelecting();
  }

  async function addItem(e) {
    e.preventDefault();
    const list = state.currentShoppingList.val;
    if (!list || !newItem.val.trim()) return;
    if (await applyItemChange(api.addShoppingItem(list.id, newItem.val))) {
      newItem.val = "";
    }
  }

  function Item(list, item) {
    if (selecting.val) {
      const order = selected.val.indexOf(item.id);
      return button(
        {
          class: order >= 0 ? "shop-item selectable selected" : "shop-item selectable",
          "aria-pressed": String(order >= 0),
          onclick: () => toggleSelected(item.id),
        },
        span({ class: "shop-pick" }, order >= 0 ? String(order + 1) : ""),
        div(
          { class: "shop-item-main" },
          ItemText(item),
          SourceLines(item),
        ),
      );
    }
    const toggle = () => applyItemChange(api.updateShoppingItem(list.id, item.id, { checked: !item.checked }));
    return div(
      { class: item.checked ? "shop-item checked" : "shop-item", "data-id": item.id },
      button(
        {
          class: "shop-grip",
          "aria-label": `Reorder ${item.display}`,
          title: "Drag to reorder, or use the arrow keys",
          onpointerdown: (e) => startDrag(e, list, item),
          onkeydown: (e) => keyMove(e, list, item),
        },
        GripIcon(),
      ),
      button(
        { class: "shop-check", role: "checkbox", "aria-checked": String(item.checked), "aria-label": item.display, onclick: toggle },
        item.checked ? CheckIcon({ size: 14, strokeWidth: 2.5 }) : null,
      ),
      div(
        { class: "shop-item-main", onclick: toggle },
        ItemText(item),
        SourceLines(item),
      ),
      RowMenu(list, item),
    );
  }

  function Header(list) {
    return div(
      { class: "recipe-detail-header" },
      div(
        {},
        h1({ class: "recipe-detail-title" }, list.name),
        div({ class: "recipe-detail-meta" }, list.progressDisplay),
      ),
      div(
        { class: "recipe-detail-actions" },
        () => (selecting.val
          ? div(
            { class: "recipe-detail-actions" },
            button({ class: "btn-ghost", onclick: exitSelecting }, "Cancel"),
            button(
              { class: "btn-primary", disabled: () => selected.val.length < 2, onclick: mergeSelected },
              () => `Merge (${selected.val.length})`,
            ),
          )
          : div(
            { class: "recipe-detail-actions" },
            button({ class: "btn-ghost", onclick: () => { selecting.val = true; } }, "Select to merge"),
            button({ class: "btn-ghost", onclick: () => navigate("pantry") }, "Pantry"),
          )),
      ),
    );
  }

  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan shop-page" },
      () => {
        const list = state.currentShoppingList.val;
        if (list) return Header(list);
        if (state.currentShoppingListLoading.val) return div({ class: "empty-state" }, "Loading…");
        return div(
          { class: "empty-state" },
          div({}, state.currentShoppingListMessage.val ?? "No shopping list."),
          button({ class: "btn-ghost shop-empty-action", onclick: () => navigate("recipes") }, "Go to recipes"),
        );
      },
      // Hidden (not re-rendered) while there's no list, so the add-item input keeps its text and
      // focus across the list updates every item change causes.
      div(
        { style: () => (state.currentShoppingList.val ? "" : "display:none") },
      () => (selecting.val
        ? div(
          { class: "shop-hint" },
          span("Pick the items to combine. The first one you pick keeps its name."),
          label(
            { class: "shop-remember" },
            input({ type: "checkbox", checked: remember, onchange: (e) => { remember.val = e.target.checked; } }),
            "Remember for future lists",
          ),
        )
        : form(
          { class: "shop-add", onsubmit: addItem },
          input({
            class: "field-input",
            placeholder: "Add an item…",
            maxlength: "200",
            "aria-label": "Add an item",
            value: newItem,
            oninput: (e) => { newItem.val = e.target.value; },
          }),
          button({ type: "submit", class: "btn-ghost" }, PlusIcon(), "Add"),
        )),
      // One container per API-built group (main list, then Pantry), so drag-reorder stays within
      // a group: startDrag/keyMove only look at a row's siblings.
      () => {
        const list = state.currentShoppingList.val;
        if (!list) return div();
        if (list.groups.length === 0) return div({ class: "empty-state" }, "This list is empty.");
        return div(
          {},
          ...list.groups.map((group) => div(
            { class: "shop-group" },
            group.title
              ? div(
                { class: "shop-group-header" },
                h2({ class: "recipe-section-title" }, group.title),
                group.note ? p({ class: "shop-group-note" }, group.note) : null,
              )
              : null,
            div({ class: "meal-plan-list shop-list" }, ...group.items.map((item) => Item(list, item))),
          )),
        );
      },
      ),
    ),
    Footer(),
    DialogHost(),
  );
}
