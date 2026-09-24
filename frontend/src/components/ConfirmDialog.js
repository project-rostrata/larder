import van from "../../lib/van-1.6.1.js";
import { state } from "../state.js";

const { div, p, h2, button } = van.tags;

// Generic confirm/cancel modal -- used for recipe deletion today, written generically since
// any future destructive action (e.g. Phase 8's shopping-list item removal) can reuse it
// rather than growing its own bespoke confirmation dialog.
export function ConfirmDialog({ title, message, confirmLabel = "Confirm", onConfirm }) {
  function close() {
    state.activeDialog.val = null;
  }

  async function handleConfirm() {
    close();
    await onConfirm();
  }

  return div(
    { class: "modal-overlay", onclick: (e) => { if (e.target.classList.contains("modal-overlay")) close(); } },
    div(
      { class: "modal-card" },
      h2({ class: "modal-title" }, title),
      p({ class: "modal-body" }, message),
      div(
        { class: "modal-actions" },
        button({ type: "button", class: "btn-ghost", onclick: close }, "Cancel"),
        button({ type: "button", class: "btn-danger", onclick: handleConfirm }, confirmLabel),
      ),
    ),
  );
}
