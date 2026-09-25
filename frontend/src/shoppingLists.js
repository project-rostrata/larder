import { state, showError, showNotice } from "./state.js";
import { api, ApiError } from "./api.js";

const message = (err, fallback) => (err instanceof ApiError ? err.message : fallback);

// The Shopping tab's list: always the current meal plan's, rebuilt by the API after any plan
// change.
export async function loadCurrentShoppingList() {
  state.currentShoppingListLoading.val = true;
  try {
    const { list, message: emptyMessage } = await api.getCurrentShoppingList();
    state.currentShoppingList.val = list;
    state.currentShoppingListMessage.val = emptyMessage;
  } catch (err) {
    showError(message(err, "Could not load the shopping list"));
  } finally {
    state.currentShoppingListLoading.val = false;
  }
}

// Every item endpoint returns the whole updated list; it simply replaces the current one. A
// merge's response may carry a notice (what was remembered), shown as-is. If the list was reset
// meanwhile (the plan changed in another tab), a 404 just reloads the current list.
// Resolves true on success so callers can reset local UI (e.g. clear an input).
export async function applyItemChange(request) {
  try {
    const list = await request;
    state.currentShoppingList.val = list;
    if (list.notice) showNotice(list.notice);
    return true;
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      await loadCurrentShoppingList();
      return false;
    }
    showError(message(err, "Could not update shopping list"));
    return false;
  }
}
