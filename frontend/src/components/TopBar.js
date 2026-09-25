import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { api } from "../api.js";
import { LogoutIcon } from "../icons.js";
import { navigate } from "../router.js";

const { div, a, span, button, nav } = van.tags;

async function handleLogout() {
  try {
    await api.logout();
  } catch (e) {
    // clearing local state regardless — a failed logout call shouldn't strand the user
    // looking logged in when they clearly asked to leave.
  }
  state.user.val = null;
}

// class is a function, not a bare state.view.val read, so it gets its own isolated binding
// rather than making whichever ancestor binding built the TopBar depend on the view.
function NavLink(label, view, isActive) {
  return button(
    {
      class: () => (isActive(state.view.val) ? "nav-link active" : "nav-link"),
      onclick: () => navigate(view),
    },
    label,
  );
}

// Reused across every logged-in view (list, detail, form), unlike shelf's TopBar, which only
// ever appeared inside FileBrowser -- shelf has exactly one logged-in view, larder has several.
export function TopBar() {
  return div(
    { class: "top-bar" },
    a(
      { href: "/", class: "wordmark", onclick: (e) => { e.preventDefault(); navigate("recipes"); } },
      "larder",
    ),
    nav(
      { class: "top-bar-nav" },
      NavLink("Recipes", "recipes", (v) => v !== "meal-plan"),
      NavLink("Meal plan", "meal-plan", (v) => v === "meal-plan"),
    ),
    div(
      { class: "top-bar-user" },
      div({ class: "avatar" }, () => (state.user.val ? state.user.val.username[0].toUpperCase() : "")),
      span({ class: "username" }, () => (state.user.val ? state.user.val.username : "")),
      button({ class: "icon-btn", "aria-label": "Log out", onclick: handleLogout }, LogoutIcon()),
    ),
  );
}
