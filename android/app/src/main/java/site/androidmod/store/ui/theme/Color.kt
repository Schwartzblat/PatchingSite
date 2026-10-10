package site.androidmod.store.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The website's palette, ported.
 *
 * `client/src/index.css` is the source of truth for this project's colours and is
 * deliberately dark-only - it sets `color-scheme: dark` and says why. The phone
 * client is the same product, so it takes the same tokens rather than inventing a
 * second palette that would drift.
 *
 * The site expresses surfaces as translucent white over a near-black page; Compose
 * wants opaque colours so that elevation overlays and scrim maths stay predictable.
 * Each token below is therefore the CSS value already composited over the layer it
 * sits on - `PapInput`, for instance, is `rgba(255,255,255,0.06)` resolved over
 * `PapCard` rather than over `PapBg`, because inputs sit on cards.
 */

// Surfaces
val PapBg = Color(0xFF05060A)            // --pap-bg
val PapCard = Color(0xFF090D18)          // --pap-card
val PapCardHover = Color(0xFF0E1422)     // --pap-card-hover
val PapElevated = Color(0xFF131A2B)      // one step above card, for menus and sheets
val PapInput = Color(0xFF181C26)         // --pap-input-bg over --pap-card

// Lines
val PapBorder = Color(0xFF191A20)        // --pap-border
val PapBorderStrong = Color(0xFF282930)  // --pap-border-strong

// Text
val PapText = Color(0xFFF4F6FF)          // --pap-text
val PapMuted = Color(0xFF9B9DA7)         // --pap-muted
val PapFaint = Color(0xFF626670)         // --pap-faint

// Accents
val PapAccent = Color(0xFF007AA4)        // --pap-accent
val PapAccentStrong = Color(0xFF1791FC)  // --pap-accent-strong
val PapAccentSoft = Color(0xFF182B3A)    // --pap-accent-soft over --pap-card
val PapOnAccent = Color(0xFF04070F)      // 6.2:1 on PapAccentStrong; white is 3.3:1 and fails

// Status
val PapDanger = Color(0xFFFF6B6B)        // --pap-danger
val PapDangerBg = Color(0xFF271818)      // --pap-danger-bg over --pap-card
val PapSuccess = Color(0xFF4ADE80)       // --pap-success
val PapSuccessBg = Color(0xFF112625)     // --pap-success-bg over --pap-card
val PapWarning = Color(0xFFFBBF24)       // --pap-warning
val PapWarningBg = Color(0xFF262219)     // --pap-warning-bg over --pap-card
