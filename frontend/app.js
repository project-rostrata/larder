import van from "./lib/van-1.6.1.js";
import { state } from "./src/state.js";
import { api } from "./src/api.js";
import { initRouter, loadForCurrentView } from "./src/router.js";
import { Login } from "./src/views/Login.js";
import { Register } from "./src/views/Register.js";
import { RecipeList } from "./src/views/RecipeList.js";
import { RecipeDetail } from "./src/views/RecipeDetail.js";
import { RecipeForm } from "./src/views/RecipeForm.js";
import { MealPlanHistory } from "./src/views/MealPlanHistory.js";
import { PastMealPlan } from "./src/views/PastMealPlan.js";
import { ShoppingList } from "./src/views/ShoppingList.js";
import { IngredientMatches } from "./src/views/IngredientMatches.js";
import { Toast } from "./src/components/Toast.js";

const { div } = van.tags;

// Only relevant while logged out — not reflected in the URL, same as shelf's app.js.
const authView = van.state("login");

function MainApp() {
  if (state.view.val === "recipe") return RecipeDetail();
  if (state.view.val === "meal-plans") return MealPlanHistory();
  if (state.view.val === "meal-plan") return PastMealPlan();
  if (state.view.val === "shopping") return ShoppingList();
  if (state.view.val === "matches") return IngredientMatches();
  if (state.view.val === "recipe-new" || state.view.val === "recipe-edit") return RecipeForm();
  return RecipeList();
}

function Root() {
  return div(
    Toast(),
    () => {
      if (!state.authChecked.val) return div({ class: "boot-loading" }, "Loading…");
      if (state.user.val) return MainApp();
      return authView.val === "register"
        ? Register({ onSwitchToLogin: () => { authView.val = "login"; } })
        : Login({ onSwitchToRegister: () => { authView.val = "register"; } });
    },
  );
}

van.add(document.getElementById("app"), Root());

initRouter();

api.me().then(
  (user) => { state.user.val = user; loadForCurrentView(); },
  () => { state.user.val = null; },
).finally(() => { state.authChecked.val = true; });
