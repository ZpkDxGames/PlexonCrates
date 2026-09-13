# PlexonCrates 6.5 GUI design system

6.5 introduces one shared presentation layer for inventory menus: `GuiTheme`, `GuiLayout`, `GuiChromeRenderer`, `GuiItemFactory`, and `GuiNavigation`.

- LIST_54 keeps the 28-card grid at 10–16, 19–25, 28–34 and 37–43.
- Footer semantics are fixed at 45 Previous, 46 Search/Refresh, 47 Context, 48 Back, 49 Primary, 50 Secondary, 51 Status, 52 Close/Cancel and 53 Next.
- PANEL_54 uses dark frame chrome plus structural separator lanes.
- DIALOG_27 keeps confirm/subject/cancel at 11/13/15.
- Decoration never binds actions and declared content/exact-input regions are cleared after chrome rendering.
- Player, admin, Test Lab, and profile surfaces render through the same theme rather than hard-coded glass fillers.

The design layer is presentation-only. Opening transactions, payment, selection, exact-item bytes, claims, persistence, draft leases and journal recovery remain outside it.
