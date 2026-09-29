import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { TopBar } from "../components/TopBar.js";
import { Footer } from "../components/Footer.js";
import { DialogHost } from "../components/DialogHost.js";

const { div, h1, button } = van.tags;

function Card(plan) {
  return button(
    { class: "shop-list-card", onclick: () => navigate("meal-plan", { id: plan.id }) },
    div({ class: "recipe-card-title" }, plan.datesDisplay),
    div({ class: "recipe-card-meta" }, plan.summaryDisplay),
  );
}

// Past (archived) plans. The current plan lives at the top of the Recipes view.
export function MealPlanHistory() {
  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan" },
      div({ class: "recipe-detail-header" }, h1({ class: "recipe-detail-title" }, "Past meal plans")),
      div(
        { class: "meal-plan-list" },
        () => {
          if (state.mealPlanHistoryLoading.val && state.mealPlanHistory.val.length === 0) {
            return div({ class: "empty-state" }, "Loading…");
          }
          if (state.mealPlanHistory.val.length === 0) {
            return div({ class: "empty-state" }, "No past meal plans yet. Starting a new plan moves the current one here.");
          }
          return div({ style: "display:contents" }, ...state.mealPlanHistory.val.map(Card));
        },
      ),
    ),
    Footer(),
    DialogHost(),
  );
}
