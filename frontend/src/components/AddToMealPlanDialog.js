import van from "../../lib/van-1.6.1.js";
import { state, showError, showNotice } from "../state.js";
import { ApiError } from "../api.js";
import { addToMealPlan } from "../mealPlan.js";

const { div, form, label, input, button, h2, p } = van.tags;

// Asks for servings when the recipe states a numeric yield, otherwise for a batch multiplier.
// Either way the value is sent as entered -- the API does the servings -> multiplier math.
export function AddToMealPlanDialog(recipe) {
  const baseServings = recipe.servings;
  const labelText = van.state("");
  const amount = van.state(String(baseServings ?? 1));
  const submitting = van.state(false);

  function close() {
    state.activeDialog.val = null;
  }

  async function submit(e) {
    e.preventDefault();
    const value = Number(amount.val);
    if (!Number.isFinite(value) || value <= 0) {
      showError(baseServings ? "Servings must be a positive number" : "Multiplier must be a positive number");
      return;
    }
    submitting.val = true;
    try {
      await addToMealPlan({
        recipeId: recipe.id,
        label: labelText.val.trim() || null,
        ...(baseServings ? { servings: value } : { servingsMultiplier: value }),
      });
      close();
      showNotice(`Added "${recipe.title}" to the meal plan`);
    } catch (err) {
      showError(err instanceof ApiError ? err.message : "Could not add to meal plan");
    } finally {
      submitting.val = false;
    }
  }

  return div(
    { class: "modal-overlay", onclick: (e) => { if (e.target.classList.contains("modal-overlay")) close(); } },
    div(
      { class: "modal-card" },
      h2({ class: "modal-title" }, "Add to meal plan"),
      p({ class: "modal-body" }, recipe.title),
      form(
        { onsubmit: submit },
        label({ class: "field-label", for: "meal-plan-label" }, "Label (optional)"),
        input({
          id: "meal-plan-label",
          class: "field-input",
          placeholder: "e.g. Monday, for guests",
          maxlength: "100",
          autofocus: true,
          oninput: (e) => { labelText.val = e.target.value; },
        }),
        label({ class: "field-label", for: "meal-plan-amount" }, baseServings ? "Servings" : "Batch multiplier"),
        input({
          id: "meal-plan-amount",
          class: "field-input",
          type: "number",
          min: "0",
          step: "any",
          value: amount,
          oninput: (e) => { amount.val = e.target.value; },
        }),
        baseServings
          ? div({ class: "field-hint" }, `Recipe as written: ${recipe.display.servings}`)
          : div({ class: "field-hint" }, "1 = the recipe as written"),
        div(
          { class: "modal-actions" },
          button({ type: "button", class: "btn-ghost", onclick: close }, "Cancel"),
          button({ type: "submit", class: "btn-primary", disabled: submitting }, () => (submitting.val ? "Adding…" : "Add")),
        ),
      ),
    ),
  );
}
