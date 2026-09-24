import van from "../../lib/van-1.6.1.js";
import { state, showError } from "../state.js";
import { api, ApiError } from "../api.js";
import { navigate } from "../router.js";

const { div, form, label, input, button, h2 } = van.tags;

export function ImportDialog() {
  const url = van.state("");
  const submitting = van.state(false);

  function close() {
    state.activeDialog.val = null;
  }

  async function submit(e) {
    e.preventDefault();
    const value = url.val.trim();
    if (!value) return;
    submitting.val = true;
    try {
      const recipe = await api.importRecipe(value);
      close();
      navigate("recipe", { id: recipe.id });
    } catch (err) {
      // 422 IMPORT_FAILED messages are already written to be shown directly to a user (see
      // RecipeImportHandler.kt) -- e.g. "No recipe data found at that URL".
      showError(err instanceof ApiError ? err.message : "Could not import that recipe");
    } finally {
      submitting.val = false;
    }
  }

  return div(
    { class: "modal-overlay", onclick: (e) => { if (e.target.classList.contains("modal-overlay")) close(); } },
    div(
      { class: "modal-card" },
      h2({ class: "modal-title" }, "Import from URL"),
      form(
        { onsubmit: submit },
        label({ class: "field-label", for: "import-url" }, "Recipe URL"),
        input({
          id: "import-url",
          class: "field-input",
          type: "url",
          placeholder: "https://example.com/a-recipe",
          autofocus: true,
          oninput: (e) => { url.val = e.target.value; },
        }),
        div(
          { class: "modal-actions" },
          button({ type: "button", class: "btn-ghost", onclick: close }, "Cancel"),
          button({ type: "submit", class: "btn-primary", disabled: submitting }, () => (submitting.val ? "Importing…" : "Import")),
        ),
      ),
    ),
  );
}
