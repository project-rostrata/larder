import { state, showError, showNotice } from "./state.js";
import { api, ApiError } from "./api.js";
import { navigate } from "./router.js";

const message = (err, fallback) => (err instanceof ApiError ? err.message : fallback);

export async function refreshShoppingLists() {
  state.shoppingListsLoading.val = true;
  try {
    state.shoppingLists.val = (await api.listShoppingLists()).shoppingLists;
  } catch (err) {
    showError(message(err, "Could not load shopping lists"));
  } finally {
    state.shoppingListsLoading.val = false;
  }
}

export async function loadShoppingList(id) {
  state.currentShoppingListLoading.val = true;
  state.currentShoppingList.val = null;
  try {
    state.currentShoppingList.val = await api.getShoppingList(id);
  } catch (err) {
    showError(message(err, "Could not load shopping list"));
  } finally {
    state.currentShoppingListLoading.val = false;
  }
}

export async function createFromMealPlan() {
  try {
    const list = await api.createShoppingList({ fromMealPlan: true });
    navigate("shopping-list", { id: list.id });
  } catch (err) {
    showError(message(err, "Could not create shopping list"));
  }
}

export async function deleteShoppingList(list) {
  try {
    await api.deleteShoppingList(list.id);
    navigate("shopping-lists");
  } catch (err) {
    showError(message(err, "Could not delete shopping list"));
  }
}

// Every item endpoint returns the whole updated list; it simply replaces the current one. A
// merge's response may carry a notice (what was remembered), shown as-is.
// Resolves true on success so callers can reset local UI (e.g. clear an input).
export async function applyItemChange(request) {
  try {
    const list = await request;
    state.currentShoppingList.val = list;
    if (list.notice) showNotice(list.notice);
    return true;
  } catch (err) {
    showError(message(err, "Could not update shopping list"));
    return false;
  }
}
