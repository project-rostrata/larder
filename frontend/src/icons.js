// Minimal inline-SVG icon set, same restrained style and icon() helper as shelf's — no icon
// library. Only the icons larder's own views actually use; add more here as they're needed,
// not speculatively.
import van from "../lib/van-1.6.1.js";

const { svg, path } = van.tags("http://www.w3.org/2000/svg");

function icon(children) {
  return (props = {}) =>
    svg(
      {
        viewBox: "0 0 24 24",
        width: props.size ?? 16,
        height: props.size ?? 16,
        fill: "none",
        stroke: props.color ?? "currentColor",
        "stroke-width": props.strokeWidth ?? 1.8,
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
      },
      ...children(),
    );
}

export const PlusIcon = icon(() => [path({ d: "M12 5v14M5 12h14" })]);
export const CloseIcon = icon(() => [path({ d: "M18 6 6 18M6 6l12 12" })]);
export const LogoutIcon = icon(() => [
  path({ d: "M9 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3" }),
  path({ d: "M16 17l5-5-5-5" }),
  path({ d: "M21 12H9" }),
]);
export const EditIcon = icon(() => [
  path({ d: "M12 20h9" }),
  path({ d: "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" }),
]);
export const TrashIcon = icon(() => [
  path({ d: "M3 6h18" }),
  path({ d: "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" }),
  path({ d: "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" }),
]);
export const CheckIcon = icon(() => [path({ d: "M20 6 9 17l-5-5" })]);
export const LinkIcon = icon(() => [
  path({ d: "M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1.5 1.5" }),
  path({ d: "M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1.5-1.5" }),
]);
