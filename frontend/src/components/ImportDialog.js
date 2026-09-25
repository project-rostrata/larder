import van from "../../lib/van-1.6.1.js";
import { state, showError, showNotice } from "../state.js";
import { api, ApiError } from "../api.js";
import { navigate } from "../router.js";
import { refreshRecipeList } from "../recipes.js";

const { div, form, label, input, button, h2, p, ul, li, span } = van.tags;

// Two ways in: a recipe web page (schema.org JSON-LD), or one or more recipe JSON files such as
// Nextcloud Cookbook's per-recipe recipe.json. Files are read as text and sent to the API
// as-is; the API does all the parsing and reports per-file results.
export function ImportDialog() {
  const url = van.state("");
  const submittingUrl = van.state(false);
  const files = van.state([]);
  const submittingFiles = van.state(false);
  const result = van.state(null); // the API's file-import response, shown when some files failed

  function close() {
    state.activeDialog.val = null;
  }

  async function submitUrl(e) {
    e.preventDefault();
    const value = url.val.trim();
    if (!value) return;
    submittingUrl.val = true;
    try {
      const recipe = await api.importRecipe(value);
      close();
      navigate("recipe", { id: recipe.id });
    } catch (err) {
      // 422 IMPORT_FAILED messages are already written to be shown directly to a user (see
      // RecipeImportHandler.kt) -- e.g. "No recipe data found at that URL".
      showError(err instanceof ApiError ? err.message : "Could not import that recipe");
    } finally {
      submittingUrl.val = false;
    }
  }

  async function submitFiles(e) {
    e.preventDefault();
    if (!files.val.length) return;
    submittingFiles.val = true;
    try {
      const payload = await Promise.all(files.val.map(async (f) => ({ name: f.name, content: await f.text() })));
      const response = await api.importRecipeFiles(payload);
      if (response.imported.length) refreshRecipeList();
      if (response.failed.length === 0) {
        close();
        showNotice(response.summary);
      } else {
        result.val = response;
      }
    } catch (err) {
      showError(err instanceof ApiError ? err.message : "Could not import those files");
    } finally {
      submittingFiles.val = false;
    }
  }

  return div(
    { class: "modal-overlay", onclick: (e) => { if (e.target.classList.contains("modal-overlay")) close(); } },
    div(
      { class: "modal-card" },
      h2({ class: "modal-title" }, "Import recipes"),
      form(
        { onsubmit: submitUrl },
        label({ class: "field-label", for: "import-url" }, "From a recipe web page"),
        div(
          { class: "import-row" },
          input({
            id: "import-url",
            class: "field-input",
            type: "url",
            placeholder: "https://example.com/a-recipe",
            oninput: (e) => { url.val = e.target.value; },
          }),
          button({ type: "submit", class: "btn-primary", disabled: submittingUrl }, () => (submittingUrl.val ? "Importing…" : "Import")),
        ),
      ),
      div({ class: "import-divider" }, span("or")),
      form(
        { onsubmit: submitFiles },
        label({ class: "field-label", for: "import-files" }, "From Nextcloud Cookbook"),
        p({ class: "field-hint import-hint" }, "Select one or more recipe.json files. Images aren't imported."),
        div(
          { class: "import-row" },
          input({
            id: "import-files",
            class: "field-input",
            type: "file",
            multiple: true,
            accept: ".json,application/json",
            onchange: (e) => { files.val = [...e.target.files]; result.val = null; },
          }),
          button(
            { type: "submit", class: "btn-primary", disabled: () => submittingFiles.val || files.val.length === 0 },
            () => (submittingFiles.val ? "Importing…" : "Import files"),
          ),
        ),
      ),
      () => {
        const r = result.val;
        if (!r) return div();
        return div(
          { class: "import-result", role: "status" },
          p({}, r.summary),
          ul({}, r.failed.map((f) => li(span({ class: "import-file" }, f.file), " — ", f.message))),
        );
      },
      div({ class: "modal-actions" }, button({ type: "button", class: "btn-ghost", onclick: close }, "Close")),
    ),
  );
}
