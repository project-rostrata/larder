import van from "../../lib/van-1.6.1.js";
import { state, showError } from "../state.js";
import { navigate } from "../router.js";
import { api, ApiError } from "../api.js";
import { TopBar } from "../components/TopBar.js";
import { PlusIcon, CloseIcon } from "../icons.js";

const { div, form, label, input, textarea, button, h1 } = van.tags;

let rowId = 0;
function makeRow(value = "") {
  return { id: rowId++, value };
}

// Structural add/remove (which rows exist) is reactive via the `rows` van.state array; typing
// within a row just mutates row.value directly and is read at submit time -- no per-keystroke
// reactivity needed, same "uncontrolled input, read on submit" idiom as the scalar fields below.
function DynamicRows({ labelText, rows, addLabel, multiline }) {
  const Field = multiline ? textarea : input;
  return div(
    {},
    label({ class: "field-label" }, labelText),
    div(
      { class: "dynamic-row-list" },
      () =>
        div(
          { style: "display:contents" },
          ...rows.val.map((row) =>
            div(
              { class: "dynamic-row" },
              Field({
                class: multiline ? "field-textarea" : "field-input",
                value: row.value,
                oninput: (e) => { row.value = e.target.value; },
              }),
              button(
                {
                  type: "button",
                  class: "icon-btn",
                  "aria-label": "Remove",
                  onclick: () => { rows.val = rows.val.filter((r) => r.id !== row.id); },
                },
                CloseIcon(),
              ),
            ),
          ),
        ),
    ),
    button(
      { type: "button", class: "btn-ghost", onclick: () => { rows.val = [...rows.val, makeRow()]; } },
      PlusIcon(), addLabel,
    ),
  );
}

function Field(labelText, props) {
  return div(
    {},
    label({ class: "field-label", for: props.id }, labelText),
    input({ class: "field-input", ...props }),
  );
}

function buildForm(initial, { submitLabel, onSubmit }) {
  const title = van.state(initial.title ?? "");
  const sourceUrl = van.state(initial.sourceUrl ?? "");
  const servings = van.state(initial.servings != null ? String(initial.servings) : "");
  const servingsText = van.state(initial.servingsText ?? "");
  const prepTimeMinutes = van.state(initial.prepTimeMinutes != null ? String(initial.prepTimeMinutes) : "");
  const cookTimeMinutes = van.state(initial.cookTimeMinutes != null ? String(initial.cookTimeMinutes) : "");
  const totalTimeMinutes = van.state(initial.totalTimeMinutes != null ? String(initial.totalTimeMinutes) : "");
  const tags = van.state((initial.tags ?? []).join(", "));
  const submitting = van.state(false);

  const instructionRows = van.state(
    (initial.instructions ?? []).length ? initial.instructions.map((s) => makeRow(s)) : [makeRow()],
  );
  const ingredientRows = van.state(
    (initial.ingredients ?? []).length
      ? initial.ingredients.map((ing) => makeRow(ing.rawText))
      : [makeRow()],
  );

  function toInt(value) {
    const trimmed = value.trim();
    if (!trimmed) return null;
    const n = Number.parseInt(trimmed, 10);
    return Number.isNaN(n) ? null : n;
  }

  async function submit(e) {
    e.preventDefault();
    if (!title.val.trim()) {
      showError("Title is required");
      return;
    }
    const body = {
      title: title.val.trim(),
      sourceUrl: sourceUrl.val.trim() || null,
      servings: toInt(servings.val),
      servingsText: servingsText.val.trim() || null,
      prepTimeMinutes: toInt(prepTimeMinutes.val),
      cookTimeMinutes: toInt(cookTimeMinutes.val),
      totalTimeMinutes: toInt(totalTimeMinutes.val),
      tags: tags.val.split(",").map((t) => t.trim()).filter((t) => t),
      instructions: instructionRows.val.map((r) => r.value.trim()).filter((v) => v),
      ingredients: ingredientRows.val
        .map((r) => r.value.trim())
        .filter((v) => v)
        .map((rawText) => ({ rawText })),
    };
    submitting.val = true;
    try {
      await onSubmit(body);
    } catch (err) {
      showError(err instanceof ApiError ? err.message : "Could not save recipe");
    } finally {
      submitting.val = false;
    }
  }

  return form(
    { class: "recipe-form", onsubmit: submit },
    h1({ class: "recipe-detail-title" }, initial.title ? `Edit ${initial.title}` : "New recipe"),
    Field("Title", { id: "title", value: title.val, oninput: (e) => { title.val = e.target.value; } }),
    Field("Source URL", { id: "sourceUrl", type: "url", value: sourceUrl.val, oninput: (e) => { sourceUrl.val = e.target.value; } }),
    div(
      { class: "form-row" },
      Field("Servings", { id: "servings", type: "number", min: "0", value: servings.val, oninput: (e) => { servings.val = e.target.value; } }),
      Field("Servings (text)", { id: "servingsText", placeholder: "e.g. 4-6", value: servingsText.val, oninput: (e) => { servingsText.val = e.target.value; } }),
    ),
    div(
      { class: "form-row" },
      Field("Prep (min)", { id: "prepTimeMinutes", type: "number", min: "0", value: prepTimeMinutes.val, oninput: (e) => { prepTimeMinutes.val = e.target.value; } }),
      Field("Cook (min)", { id: "cookTimeMinutes", type: "number", min: "0", value: cookTimeMinutes.val, oninput: (e) => { cookTimeMinutes.val = e.target.value; } }),
      Field("Total (min)", { id: "totalTimeMinutes", type: "number", min: "0", value: totalTimeMinutes.val, oninput: (e) => { totalTimeMinutes.val = e.target.value; } }),
    ),
    Field("Tags", { id: "tags", placeholder: "comma, separated", value: tags.val, oninput: (e) => { tags.val = e.target.value; } }),
    DynamicRows({ labelText: "Ingredients", rows: ingredientRows, addLabel: "Add ingredient", multiline: false }),
    DynamicRows({ labelText: "Instructions", rows: instructionRows, addLabel: "Add step", multiline: true }),
    div(
      { class: "form-actions" },
      button({ type: "button", class: "btn-ghost", onclick: () => window.history.back() }, "Cancel"),
      button({ type: "submit", class: "btn-primary", disabled: submitting }, () => (submitting.val ? "Saving…" : submitLabel)),
    ),
  );
}

export function RecipeForm() {
  const isEdit = state.view.val === "recipe-edit";

  return div(
    { class: "app-shell" },
    TopBar(),
    () => {
      if (!isEdit) {
        return buildForm(
          {},
          {
            submitLabel: "Create recipe",
            onSubmit: async (body) => {
              const recipe = await api.createRecipe(body);
              navigate("recipe", { id: recipe.id });
            },
          },
        );
      }
      if (state.currentRecipeLoading.val && !state.currentRecipe.val) {
        return div({ class: "empty-state" }, "Loading…");
      }
      if (!state.currentRecipe.val) {
        return div({ class: "empty-state" }, "Recipe not found.");
      }
      const recipe = state.currentRecipe.val;
      return buildForm(recipe, {
        submitLabel: "Save changes",
        onSubmit: async (body) => {
          await api.updateRecipe(recipe.id, body);
          navigate("recipe", { id: recipe.id });
        },
      });
    },
  );
}
