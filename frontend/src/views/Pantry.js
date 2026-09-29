import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { navigate } from "../router.js";
import { addToPantryByName, removeFromPantry } from "../pantry.js";
import { TopBar } from "../components/TopBar.js";
import { Footer } from "../components/Footer.js";
import { CloseIcon, PlusIcon } from "../icons.js";

const { div, h1, p, span, button, form, input } = van.tags;

// The user's pantry: staples that stay on the shopping list without an amount. Reached from
// the shopping list (no nav tab). router.js loads state.pantry; this renders it.
export function Pantry() {
  const newName = van.state("");

  async function add(e) {
    e.preventDefault();
    if (!newName.val.trim()) return;
    if (await addToPantryByName(newName.val)) newName.val = "";
  }

  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan shop-page" },
      div(
        { class: "recipe-detail-header" },
        h1({ class: "recipe-detail-title" }, "Pantry"),
        button({ class: "btn-ghost", onclick: () => navigate("shopping") }, "Shopping list"),
      ),
      () => p({ class: "matches-summary" }, state.pantry.val?.summary ?? (state.pantryLoading.val ? "Loading…" : "")),
      form(
        { class: "shop-add", onsubmit: add },
        input({
          class: "field-input",
          placeholder: "e.g. salt, olive oil, butter",
          maxlength: "100",
          "aria-label": "Add to pantry",
          value: newName,
          oninput: (e) => { newName.val = e.target.value; },
        }),
        button({ type: "submit", class: "btn-ghost" }, PlusIcon(), "Add"),
      ),
      () => {
        const items = state.pantry.val?.items ?? [];
        if (!items.length) return div();
        return div(
          { class: "match-list" },
          ...items.map((item) => div(
            { class: "pantry-row" },
            span({ class: "pantry-name" }, item.name),
            button(
              { class: "icon-btn", "aria-label": `Remove ${item.name} from pantry`, onclick: () => removeFromPantry(item.ingredientId) },
              CloseIcon(),
            ),
          )),
        );
      },
    ),
    Footer(),
  );
}
