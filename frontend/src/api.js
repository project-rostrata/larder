// Fetch wrappers — one function per endpoint. Same request()/ApiError pattern as shelf's
// api.js; larder has no chunked-upload equivalent, so unlike shelf's, everything here is a
// plain JSON fetch() call, nothing needs XHR.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(method, path, body) {
  const hasBody = body !== undefined;
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: hasBody ? { "Content-Type": "application/json" } : undefined,
    body: hasBody ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const err = data && data.error ? data.error : { code: "UNKNOWN", message: res.statusText };
    throw new ApiError(res.status, err.code, err.message);
  }
  return data;
}

export const api = {
  register: (username, password) => request("POST", "/api/register", { username, password }),
  login: (username, password) => request("POST", "/api/login", { username, password }),
  logout: () => request("POST", "/api/logout"),
  me: () => request("GET", "/api/me"),

  listRecipes: (tag) => {
    const q = tag ? `?${new URLSearchParams({ tag }).toString()}` : "";
    return request("GET", `/api/recipes${q}`);
  },
  getRecipe: (id) => request("GET", `/api/recipes/${id}`),
  createRecipe: (body) => request("POST", "/api/recipes", body),
  updateRecipe: (id, body) => request("PUT", `/api/recipes/${id}`, body),
  deleteRecipe: (id) => request("DELETE", `/api/recipes/${id}`),
  importRecipe: (url) => request("POST", "/api/recipes/import", { url }),

  listMealPlan: () => request("GET", "/api/meal-plan"),
  addToMealPlan: (body) => request("POST", "/api/meal-plan", body),
  removeFromMealPlan: (id) => request("DELETE", `/api/meal-plan/${id}`),

  listShoppingLists: () => request("GET", "/api/shopping-lists"),
  getShoppingList: (id) => request("GET", `/api/shopping-lists/${id}`),
  createShoppingList: (body) => request("POST", "/api/shopping-lists", body),
  deleteShoppingList: (id) => request("DELETE", `/api/shopping-lists/${id}`),
  addShoppingItem: (id, text) => request("POST", `/api/shopping-lists/${id}/items`, { text }),
  updateShoppingItem: (id, itemId, body) => request("PATCH", `/api/shopping-lists/${id}/items/${itemId}`, body),
  deleteShoppingItem: (id, itemId) => request("DELETE", `/api/shopping-lists/${id}/items/${itemId}`),
  mergeShoppingItems: (id, itemIds, remember) =>
    request("POST", `/api/shopping-lists/${id}/items/merge`, { itemIds, remember }),
};
