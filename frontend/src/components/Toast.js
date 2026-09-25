import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";
import { CloseIcon } from "../icons.js";
import { emptyNode } from "../vanHelpers.js";

const { div, span, button } = van.tags;

export function Toast() {
  return div(
    { class: "toast-container" },
    () => {
      const error = state.error.val;
      const message = error ?? state.notice.val;
      if (!message) return emptyNode();
      return div(
        { class: error ? "toast" : "toast toast-notice", role: error ? "alert" : "status" },
        span({ class: "toast-message" }, message),
        button(
          {
            class: "icon-btn",
            "aria-label": "Dismiss",
            onclick: () => { state.error.val = null; state.notice.val = null; },
          },
          CloseIcon({ size: 14 }),
        ),
      );
    },
  );
}
