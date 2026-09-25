import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { api } from "../api.js";
import { applyItemChange } from "../shoppingLists.js";
import { TopBar } from "../components/TopBar.js";
import { DialogHost } from "../components/DialogHost.js";
import { CheckIcon, CloseIcon, TrashIcon, PlusIcon } from "../icons.js";

const { div, h1, span, button, form, input, label } = van.tags;

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
          span({ class: "shop-item-text" }, item.display),
          item.sourcesDisplay ? div({ class: "shop-item-sources" }, item.sourcesDisplay) : null,
        ),
      );
    }
    const toggle = () => applyItemChange(api.updateShoppingItem(list.id, item.id, { checked: !item.checked }));
    return div(
      { class: item.checked ? "shop-item checked" : "shop-item" },
      button(
        { class: "shop-check", role: "checkbox", "aria-checked": String(item.checked), "aria-label": item.display, onclick: toggle },
        item.checked ? CheckIcon({ size: 14, strokeWidth: 2.5 }) : null,
      ),
      div(
        { class: "shop-item-main", onclick: toggle },
        span({ class: "shop-item-text" }, item.display),
        item.sourcesDisplay ? div({ class: "shop-item-sources" }, item.sourcesDisplay) : null,
      ),
      button(
        {
          class: "icon-btn",
          "aria-label": `Remove ${item.display}`,
          onclick: () => applyItemChange(api.deleteShoppingItem(list.id, item.id)),
        },
        CloseIcon(),
      ),
    );
  }

  function Header(list) {
    return div(
      { class: "recipe-detail-header" },
      div(
        {},
        h1({ class: "recipe-detail-title" }, list.name),
        div({ class: "recipe-detail-meta" }, `${list.createdDisplay} · ${list.progressDisplay}`),
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
            button(
              { class: "btn-danger", onclick: () => { state.activeDialog.val = { type: "confirmDeleteList", list }; } },
              TrashIcon(), "Delete",
            ),
          )),
      ),
    );
  }

  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan" },
      () => {
        const list = state.currentShoppingList.val;
        if (!list) {
          return div({ class: "empty-state" }, state.currentShoppingListLoading.val ? "Loading…" : "Shopping list not found.");
        }
        return Header(list);
      },
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
      div(
        { class: "meal-plan-list" },
        () => {
          const list = state.currentShoppingList.val;
          if (!list) return div();
          if (list.items.length === 0) return div({ class: "empty-state" }, "This list is empty.");
          return div({ style: "display:contents" }, ...list.items.map((item) => Item(list, item)));
        },
      ),
    ),
    DialogHost(),
  );
}
