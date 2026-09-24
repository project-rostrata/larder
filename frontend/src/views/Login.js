import van from "../../lib/van-1.6.1.js";
import { state, showError } from "../state.js";
import { api, ApiError } from "../api.js";

const { div, form, label, input, button, span, a } = van.tags;

export function Login({ onSwitchToRegister }) {
  const username = van.state("");
  const password = van.state("");
  const submitting = van.state(false);

  async function submit(e) {
    e.preventDefault();
    submitting.val = true;
    try {
      state.user.val = await api.login(username.val, password.val);
    } catch (err) {
      showError(err instanceof ApiError ? err.message : "Could not sign in");
    } finally {
      submitting.val = false;
    }
  }

  return div(
    { class: "auth-shell" },
    form(
      { class: "auth-card", onsubmit: submit },
      div(
        { class: "auth-brand" },
        span({ class: "auth-wordmark" }, "larder"),
        span({ class: "auth-subtitle" }, "Sign in to your recipes"),
      ),
      div(
        { class: "auth-fields" },
        div(
          label({ class: "field-label", for: "login-username" }, "Username"),
          input({
            id: "login-username", class: "field-input", autocomplete: "username", autofocus: true,
            oninput: (e) => { username.val = e.target.value; },
          }),
        ),
        div(
          label({ class: "field-label", for: "login-password" }, "Password"),
          input({
            id: "login-password", class: "field-input", type: "password", autocomplete: "current-password",
            oninput: (e) => { password.val = e.target.value; },
          }),
        ),
      ),
      button({ type: "submit", class: "btn-primary-block", disabled: submitting }, "Sign in"),
      div(
        { class: "auth-switch" },
        "Need an account? ",
        a({ href: "#", onclick: (e) => { e.preventDefault(); onSwitchToRegister(); } }, "Register"),
      ),
    ),
  );
}
