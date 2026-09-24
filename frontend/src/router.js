// Query-param view state (?view=...&id=...&tag=...) on the single / route, not real path
// segments — matching shelf's own router.js pattern exactly, per the human's explicit
// direction. The static file server always serves index.html for GET / regardless of query
// string, so this needs no SPA-fallback logic there, unlike real path-based routing would.
//
// navigate()/popstate call the loader directly rather than relying on a reactive derivation of
// state.view to trigger it — same reasoning as shelf's router.js: a data fetch as a bare side
// effect isn't guaranteed the same lifecycle a rendered binding gets, so explicit is simpler to
// reason about here.
import { state } from "./state.js";
import { refreshRecipeList, loadRecipe } from "./recipes.js";

function paramsFromUrl() {
  const params = new URLSearchParams(window.location.search);
  return {
    view: params.get("view") || "recipes",
    id: params.get("id") || null,
    tag: params.get("tag") || "",
  };
}

function loadForCurrentView() {
  if (state.view.val === "recipes") {
    refreshRecipeList();
  } else if (state.view.val === "recipe" || state.view.val === "recipe-edit") {
    if (state.currentRecipeId.val) loadRecipe(state.currentRecipeId.val);
  }
}

function applyUrlToState() {
  const { view, id, tag } = paramsFromUrl();
  state.view.val = view;
  state.currentRecipeId.val = id;
  state.tagFilter.val = tag;
  loadForCurrentView();
}

export function initRouter() {
  applyUrlToState();
  window.addEventListener("popstate", applyUrlToState);
}

export function navigate(view, { id, tag } = {}) {
  const params = new URLSearchParams();
  if (view && view !== "recipes") params.set("view", view);
  if (id) params.set("id", id);
  if (tag) params.set("tag", tag);
  const query = params.toString();
  window.history.pushState({}, "", window.location.pathname + (query ? `?${query}` : ""));
  state.view.val = view;
  state.currentRecipeId.val = id || null;
  state.tagFilter.val = tag || "";
  loadForCurrentView();
}
