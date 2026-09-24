import van from "../../lib/van-1.6.1.js";
import { formatMinutes } from "../format.js";
import { navigate } from "../router.js";

const { div, span, button } = van.tags;

export function RecipeCard(recipe) {
  const meta = [];
  if (recipe.servings) meta.push(`${recipe.servings} servings`);
  const totalTime = formatMinutes(recipe.totalTimeMinutes);
  if (totalTime) meta.push(totalTime);

  return button(
    { class: "recipe-card", onclick: () => navigate("recipe", { id: recipe.id }) },
    div({ class: "recipe-card-title" }, recipe.title),
    meta.length ? div({ class: "recipe-card-meta" }, meta.map((m) => span(m))) : null,
    recipe.tags.length
      ? div({ class: "recipe-card-tags" }, recipe.tags.map((t) => span({ class: "tag-chip" }, t)))
      : null,
  );
}
