# Minikun — Design system

This is the design reference for future Minikun web interface work. The agreed direction is **calm forest green, playful companionship, and an initial view that fits the screen without scrolling the whole page**.

Visual reference: [design-preview.html](src/main/resources/static/cockpit/design-preview.html). The application entry point is [index.html](src/main/resources/static/cockpit/index.html); shared visual and viewport rules live in [minikun-minimal.css](src/main/resources/static/cockpit/minikun-minimal.css). Reuse the application's existing components and behavior when implementing further work.

## Personality and atmosphere

- Combine Linear's orderly hierarchy with Claude-inspired reading comfort, while retaining Minikun's own green identity.
- Minikun is a companion and a work partner. Add personality through the existing avatar, sticker treatments, speech bubbles, muted pastel accents, and small details.
- Keep the main canvas calm and essential information easy to read. Decoration should support the interface.
- Reuse `src/main/resources/static/cockpit/minikun-avatar.jpg`. Do not substitute another mascot or borrow reference brands' logos.

## Color palette

| Token | Value | Role |
| --- | --- | --- |
| `--canvas` | `#0d1411` | Deep forest canvas |
| `--surface` | `#18221c` | Composer and elevated surfaces |
| `--accent` | `#b5d7a1` | Primary actions, selection, and focus |
| `--peach` | `#edbfa4` | Warm emphasis, speech bubbles, and note stickers |
| `--sky` | `#adc5ed` | Search and exploration |
| Soft lavender | `#c5bce7` | Tool accents, used sparingly |
| `--ink` | `#f0eee6` | Cream primary text |
| `--muted` | `#a6afa4` | Secondary text |
| `--line` | `#2a352d` | Hairline borders and dividers |

Starter cards use muted green `#1d291d`, peach `#2c241f`, blue `#1d252e`, and lavender `#26232e` surfaces. Primary buttons use pale green with dark text. Pastels identify content categories; they must not replace semantic success, warning, or error colors.

## Typography and spacing

- UI font stack: `"Sukhumvit Set", "Thonburi", "Noto Sans Thai", system-ui, sans-serif`. Reuse available fonts before adding files or dependencies.
- Main text and composer input are approximately 16px. Supporting text is approximately 12–14px; short captions may be smaller where they remain readable.
- Display headings are approximately 24–44px, adapting to both viewport width and height. Use weights 500–600; avoid negative tracking that crowds Thai text.
- Thai body line height is approximately 1.65–1.9; headings use approximately 1.3–1.5.
- Prefer a 4, 8, 12, 16, 24, 32px spacing scale. Reduce decorative space before shrinking text.

## Core rule: fit the viewport

1. The initial view should show essential content, primary actions, composer, and navigation together without document scrolling.
2. Use the actual available height, such as `100dvh`, accounting for headers, navigation, and safe areas. Use flex/grid with `min-height: 0` for content regions.
3. Keep composer and navigation outside the conversation's scrolling region. Long conversations, lists, and expanded details scroll inside their own content region.
4. On short screens, reduce gaps and decorative imagery first, then collapse secondary details or omit optional introductory copy. Essential content and actions must remain accessible.
5. Do not fake a screen-fitting layout by clipping essential content, hiding scrollbars on overflowing content, scaling the entire interface, or disabling browser zoom. An app shell with `overflow: hidden` must provide accessible scrolling regions for overflow.
6. When text is enlarged, the keyboard opens, or space is exceptionally limited, preserve readability and access to controls. Allow internal scrolling where needed.

## Layout and responsive behavior

| Condition | Starting point |
| --- | --- |
| Desktop | Approximately 224px sidebar and 64px header; chat content up to 740px and composer up to 800px wide |
| Width up to 800px | Sidebar may shrink to approximately 180px; Today becomes one column, with appointments available through a disclosure |
| Mobile | Approximately 60px bottom navigation plus safe area, and approximately 54px header; move secondary navigation and history into an accessible menu |
| Short viewport | Reduce decoration and spacing, compact starter cards, and collapse secondary information |

Use available space as the deciding factor. These values come from the prototype and may adapt to existing behavior, including the application's mobile navigation breakpoint. They are not reasons to overflow or truncate content.

## Components

- **Chat:** A sticker avatar and short greeting, clear heading, four starter actions in a 2×2 grid, and the composer below. Long messages scroll within the conversation region.
- **Starter cards:** Muted pastel fills, thin borders, approximately 18px corners with one approximately 6px corner. Use SVG icons colored for the category.
- **Avatar:** Cream outline, rounded corners, slight rotation, and a short sticker shadow. A small speech bubble or sparkle is welcome when it does not cover controls.
- **Composer:** Dark elevated surface, approximately 22px corners, pale green focus border, a send target of at least 44×44px, and input text of at least 16px.
- **Today:** Prioritize active work and status. Use rows and dividers instead of layers of nested cards. Encouraging notes are optional decoration and may disappear on short screens.
- **Appointments and system details:** Use native disclosure controls such as `<details>` for secondary information. Show a descriptive label and a count when available.
- **Status:** Include text rather than relying only on color. Live status and data must come from the application.

## Interaction and copy

- Use small hover responses, such as a 3px lift or slight sticker rotation, with 150–200ms transitions. Respect `prefers-reduced-motion`.
- Controls use SVG icons, accessible labels, visible keyboard focus, and touch targets of at least 44×44px.
- Maintain at least 4.5:1 contrast for normal text. Scrolling regions must be accessible using a keyboard.
- Product copy remains concise, friendly Thai in Minikun's voice. Action labels state their result. Playfulness must not obscure commands or status.
- Preserve existing functionality when applying the design. Use actual application data and responses; fictional data, sample replies, and preview labels belong only in the prototype.

## Acceptance criteria

- Inspect actual rendered screens at desktop, tablet, and mobile sizes, including short screens: examples are 1366×768, 768×768, 390×844, 320×740, and 320×568.
- The initial view has no horizontal or vertical document overflow. Verify that its content is visible, not merely that the scrollbar is absent.
- Test long conversations or lists and expanded details. Content remains fully readable while headers, navigation, and primary controls remain usable.
- Check keyboard focus, touch interaction, enlarged text, and reduced motion for the changed interface.
- Prefer existing CSS, native elements, and installed capabilities before adding libraries or abstractions.

For an authorized UI task with clear scope, design, implement, and verify against this document directly. Do not reconfirm routine design choices already covered here.
