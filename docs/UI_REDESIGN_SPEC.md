# FindMyCar — UI/UX & Responsive Bento Auto-Layout Spec

## 1. Design Tokens (`ui/theme/Color.kt`)
- **Background Base:** `#0B0F14` (`NearBlackBg`) with a subtle bottom radial glow (`#2EE6A6` at `10%` alpha) behind the primary thumb dock.
- **Bento Card Surface:** `#131A22` at `88%` alpha (`BentoCardSurface`), `24.dp` corner radius, and `1.dp` hairline border (`Color.White.copy(alpha = 0.08f)`, `BentoCardBorder`).
- **Accent & Semantic Colors:**
  - Primary Accent: `#2EE6A6` (`MintAccent`)
  - Warning / Underground: `#FFB020` (`WarningAmber`)
  - Destructive: `#FF5A5F` (`ErrorRed`)

## 2. 3-Zone Responsive Auto-Layout (22:9 & Foldables)
Both `HomeScreen` and `FindCarScreen` use `Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp))` divided into:
1. **Top Header (`wrapContentHeight`):** App title + live auto-detect status dot on left; `48.dp` glassmorphic circular action buttons on right (`History` & `Settings` on Home; `Close` + `FloorBadge` on FindCar).
2. **Flexible Middle Bento Zone (`Modifier.weight(1f)`):** Dynamically expands to fill vertical height on tall `22:9` displays (`412x1008dp` Galaxy Z Flip6 unfolded) with `12.dp` gaps, eliminating dead black voids.
3. **Bottom Thumb Dock (`wrapContentHeight`):** Anchored at the bottom within one-handed thumb reach (`>= 56.dp` touch targets; `72.dp` pill CTA on Home empty state; `64.dp` primary dock on Home active state; unified `28.dp`-top-corner frosted dock on FindCar).

## 3. Foldable / Compact Guard (`BoxWithConstraints`)
- When `maxHeight < 450.dp` (Z Flip6 cover screen or split-screen multi-window):
  - Secondary Bento cards (Sensors card, Recent Spot / 3-step readiness card, permission banner, secondary anchor edit button) automatically collapse.
  - Only the primary status metric and primary thumb CTA render without clipping.
