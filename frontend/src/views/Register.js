import van from "../../lib/van-1.6.1.js";
import { state, showError } from "../state.js";
import { api, ApiError } from "../api.js";

const { div, form, label, input, button, span, a } = van.tags;

export function Register({ onSwitchToLogin }) {
  const username = van.state("");
  const password = van.state("");
  const submitting = van.state(false);

  async function submit(e) {
    e.preventDefault();
    submitting.val = true;
    try {
      state.user.val = await api.register(username.val, password.val);
    } catch (err) {
      showError(err instanceof ApiError ? err.message : "Could not register");
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
        span({ class: "auth-subtitle" }, "Create your account"),
      ),
      div(
        { class: "auth-fields" },
        div(
          label({ class: "field-label", for: "reg-username" }, "Username"),
          input({
            id: "reg-username", class: "field-input", autocomplete: "username", autofocus: true,
            oninput: (e) => { username.val = e.target.value; },
          }),
        ),
        div(
          label({ class: "field-label", for: "reg-password" }, "Password"),
          input({
            id: "reg-password", class: "field-input", type: "password", autocomplete: "new-password",
            oninput: (e) => { password.val = e.target.value; },
          }),
        ),
        div({ class: "field-hint" }, "At least 8 characters."),
      ),
      button({ type: "submit", class: "btn-primary-block", disabled: submitting }, "Create account"),
      div(
        { class: "auth-switch" },
        "Already have an account? ",
        a({ href: "#", onclick: (e) => { e.preventDefault(); onSwitchToLogin(); } }, "Sign in"),
      ),
    ),
  );
}
