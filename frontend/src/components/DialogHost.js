import van from "../../lib/van-1.6.1.js";
import { state, showError } from "../state.js";
import { api, ApiError } from "../api.js";
import { ImportDialog } from "./ImportDialog.js";
import { ConfirmDialog } from "./ConfirmDialog.js";
import { AddToMealPlanDialog } from "./AddToMealPlanDialog.js";
import { refreshRecipeList } from "../recipes.js";
import { navigate } from "../router.js";
import { deleteShoppingList } from "../shoppingLists.js";
import { emptyNode } from "../vanHelpers.js";

const { div } = van.tags;

async function deleteRecipe(recipe) {
  try {
    await api.deleteRecipe(recipe.id);
    navigate("recipes");
    await refreshRecipeList();
  } catch (err) {
    showError(err instanceof ApiError ? err.message : "Could not delete recipe");
  }
}

// Reused across every logged-in view, same as TopBar -- whichever view is active can open a
// dialog by just setting state.activeDialog.
export function DialogHost() {
  return div(
    {},
    () => {
      const dialog = state.activeDialog.val;
      if (!dialog) return emptyNode();
      if (dialog.type === "import") return ImportDialog();
      if (dialog.type === "addToMealPlan") return AddToMealPlanDialog(dialog.recipe);
      if (dialog.type === "confirmDelete") {
        return ConfirmDialog({
          title: "Delete recipe?",
          message: `"${dialog.recipe.title}" will be permanently removed from your recipe list.`,
          confirmLabel: "Delete",
          onConfirm: () => deleteRecipe(dialog.recipe),
        });
      }
      if (dialog.type === "confirmDeleteList") {
        return ConfirmDialog({
          title: "Delete shopping list?",
          message: `"${dialog.list.name}" will be permanently deleted.`,
          confirmLabel: "Delete",
          onConfirm: () => deleteShoppingList(dialog.list),
        });
      }
      return emptyNode();
    },
  );
}
