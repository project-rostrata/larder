import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { TopBar } from "../components/TopBar.js";
import { DialogHost } from "../components/DialogHost.js";
import { EditIcon, TrashIcon, LinkIcon, PlusIcon } from "../icons.js";

const { div, h1, h2, a, span, ul, li, ol, button } = van.tags;

function MetaLine(recipe) {
  if (!recipe.display.details.length) return null;
  return div({ class: "recipe-detail-meta" }, recipe.display.details.map((d) => span(d)));
}

function Ingredients(recipe) {
  if (!recipe.ingredients.length) return null;
  return div(
    {},
    h2({ class: "recipe-section-title" }, "Ingredients"),
    // raw_text is the display default (see AGENTS.md/PROJECT_BRIEF.md) -- what was typed or
    // imported is what's shown, not a reconstructed "quantity + unit + name" sentence.
    ul({ class: "ingredient-list" }, recipe.ingredients.map((ing) => li(ing.rawText))),
  );
}

function Instructions(recipe) {
  if (!recipe.instructions.length) return null;
  return div(
    {},
    h2({ class: "recipe-section-title" }, "Instructions"),
    ol({ class: "instruction-list" }, recipe.instructions.map((step) => li(step))),
  );
}

function DetailContent(recipe) {
  return div(
    { class: "recipe-detail" },
    div(
      { class: "recipe-detail-header" },
      div(
        {},
        h1({ class: "recipe-detail-title" }, recipe.title),
        MetaLine(recipe),
        recipe.tags.length
          ? div({ class: "recipe-card-tags" }, recipe.tags.map((t) => span({ class: "tag-chip" }, t)))
          : null,
        recipe.sourceUrl
          ? a({ class: "recipe-detail-source", href: recipe.sourceUrl, target: "_blank", rel: "noopener noreferrer" }, LinkIcon(), "Original source")
          : null,
      ),
      div(
        { class: "recipe-detail-actions" },
        button(
          { class: "btn-primary", onclick: () => { state.activeDialog.val = { type: "addToMealPlan", recipe }; } },
          PlusIcon(), "Add to meal plan",
        ),
        button(
          { class: "btn-ghost", onclick: () => navigate("recipe-edit", { id: recipe.id }) },
          EditIcon(), "Edit",
        ),
        button(
          { class: "btn-danger", onclick: () => { state.activeDialog.val = { type: "confirmDelete", recipe }; } },
          TrashIcon(), "Delete",
        ),
      ),
    ),
    Ingredients(recipe),
    Instructions(recipe),
  );
}

export function RecipeDetail() {
  return div(
    { class: "app-shell" },
    TopBar(),
    () => {
      if (state.currentRecipeLoading.val && !state.currentRecipe.val) {
        return div({ class: "empty-state" }, "Loading…");
      }
      if (!state.currentRecipe.val) {
        return div({ class: "empty-state" }, "Recipe not found.");
      }
      return DetailContent(state.currentRecipe.val);
    },
    DialogHost(),
  );
}
