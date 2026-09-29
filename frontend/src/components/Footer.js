import van from "../../lib/van-1.6.1.js";
import { MoonIcon, SunIcon } from "../icons.js";
import { theme, toggleTheme } from "../theme.js";

const { footer, button, span } = van.tags;

// Bottom of every logged-in view, like TopBar. Only the light/dark toggle for now: it's rarely
// used, so it lives here rather than competing for room in the top bar on phones. The button
// names what a click switches to.
export function Footer() {
  return footer(
    { class: "app-footer" },
    button(
      { class: "theme-toggle", onclick: toggleTheme },
      () => (theme.val === "light" ? MoonIcon() : SunIcon()),
      span(() => (theme.val === "light" ? "Dark mode" : "Light mode")),
    ),
  );
}
