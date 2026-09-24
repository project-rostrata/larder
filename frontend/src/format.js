// Ingredient lines display as raw_text by default (see AGENTS.md/PROJECT_BRIEF.md's "what you
// typed/imported is what you see" principle, carried through to the UI) — so there's no
// fraction-quantity formatter here yet, only what prep/cook/total time actually needs.
export function formatMinutes(minutes) {
  if (minutes == null) return null;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m} min`;
  if (m === 0) return `${h} hr`;
  return `${h} hr ${m} min`;
}
