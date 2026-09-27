import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { TopBar } from "../components/TopBar.js";

const { div, h1, span, p } = van.tags;

function Match(m) {
  return div(
    { class: "match-row" },
    div({ class: "match-name" }, m.name),
    div({ class: "match-aliases" }, m.aliases.map((a) => span({ class: "tag-chip" }, a))),
  );
}

// Learned ingredient matches: each ingredient and the other spellings that resolve to it.
// Deliberately not linked from the UI -- reached only at /?view=matches. Read-only.
export function IngredientMatches() {
  return div(
    { class: "app-shell" },
    TopBar(),
    div(
      { class: "meal-plan" },
      div({ class: "recipe-detail-header" }, h1({ class: "recipe-detail-title" }, "Ingredient matches")),
      () => {
        const data = state.ingredientMatches.val;
        if (!data) return div({ class: "empty-state" }, state.ingredientMatchesLoading.val ? "Loading…" : "");
        return div(
          {},
          p({ class: "matches-summary" }, data.summary),
          data.matches.length
            ? div({ class: "match-list" }, div({ class: "match-row match-head" }, span("Ingredient"), span("Also matches")), ...data.matches.map(Match))
            : div(),
        );
      },
    ),
  );
}
