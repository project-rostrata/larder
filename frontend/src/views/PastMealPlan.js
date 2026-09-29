import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { startNewPlan } from "../mealPlan.js";
import { TopBar } from "../components/TopBar.js";
import { Footer } from "../components/Footer.js";
import { DialogHost } from "../components/DialogHost.js";
import { PlanEntryCard } from "../components/PlanEntryCard.js";

const { div, h1, button } = van.tags;

function Content(plan) {
  return div(
    {},
    div(
      { class: "recipe-detail-header" },
      h1({ class: "recipe-detail-title" }, plan.datesDisplay),
      plan.active
        ? null
        : button(
          {
            class: "btn-primary",
            onclick: () => startNewPlan({
              fromPlanId: plan.id,
              notice: "Meal plan restored",
              onDone: () => navigate("recipes"),
            }),
          },
          "Use again",
        ),
    ),
    plan.entries.length
      ? div({ class: "recipe-grid plan-grid" }, ...plan.entries.map((e) => PlanEntryCard(e)))
      : div({ class: "empty-state" }, "This plan had no recipes."),
  );
}

// router.js loads state.currentPlan; this only renders it.
export function PastMealPlan() {
  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan" },
      () => {
        const plan = state.currentPlan.val;
        if (!plan) return div({ class: "empty-state" }, state.currentPlanLoading.val ? "Loading…" : "Meal plan not found.");
        return Content(plan);
      },
    ),
    Footer(),
    DialogHost(),
  );
}
