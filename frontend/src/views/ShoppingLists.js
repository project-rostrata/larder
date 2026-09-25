import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { createFromMealPlan } from "../shoppingLists.js";
import { TopBar } from "../components/TopBar.js";
import { DialogHost } from "../components/DialogHost.js";

const { div, h1, button } = van.tags;

function Card(list) {
  return button(
    { class: "shop-list-card", onclick: () => navigate("shopping-list", { id: list.id }) },
    div({ class: "recipe-card-title" }, list.name),
    div({ class: "recipe-card-meta" }, `${list.createdDisplay} · ${list.progressDisplay}`),
  );
}

// router.js loads state.shoppingLists on navigation to this view; this only renders it.
export function ShoppingLists() {
  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan" },
      div(
        { class: "recipe-detail-header" },
        h1({ class: "recipe-detail-title" }, "Shopping lists"),
        button({ class: "btn-primary", onclick: createFromMealPlan }, "New from meal plan"),
      ),
      div(
        { class: "meal-plan-list" },
        () => {
          if (state.shoppingListsLoading.val && state.shoppingLists.val.length === 0) {
            return div({ class: "empty-state" }, "Loading…");
          }
          if (state.shoppingLists.val.length === 0) {
            return div({ class: "empty-state" }, "No shopping lists yet — plan some recipes, then choose “New from meal plan”.");
          }
          return div({ style: "display:contents" }, ...state.shoppingLists.val.map(Card));
        },
      ),
    ),
    DialogHost(),
  );
}
