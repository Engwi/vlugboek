# Vlugboek — Look & Feel Design Specification

## 1. Design Philosophy

Vlugboek must present as a **premium South African pigeon racing platform**.

The product should visually balance:

### Heritage
The long-standing tradition of wedvlug culture.

### Prestige
Competitive sport, rankings, achievement, status.

### Trust
Official race result publication.

### Modern Accessibility
Fast, responsive, mobile-first digital experience.

The interface must feel:

- elegant
- clean
- serious
- established
- proudly local
- premium but not flashy

The emotional tone should feel like:

> “The official digital home of South African wedvlug results.”

---

# 2. Visual Personality

The target visual language is:

## Sophisticated Sporting Heritage

A blend of:

- premium sporting publication
- aviation precision
- race-day anticipation
- traditional loft culture
- modern digital polish

Think:

- Rolex timing aesthetic
- premium motorsport dashboards
- heritage sporting clubs
- elegant annual record books

Avoid:

- overly playful design
- startup neon tech style
- harsh enterprise admin dashboards
- cartoon pigeon visuals
- overly bright modern gradients

---

# 3. Hero Experience

The landing page must immediately communicate:

## Pride
This is a respected sporting platform.

## Emotion
Connection to pigeon racing culture.

## Authority
Official results platform.

## Simplicity
Easy access to results.

The supplied target reference demonstrates the ideal composition:

### Left Side

Strong typographic messaging.

Large brand title:

**Vlugboek**

Supporting sub-heading.

Clear CTA buttons.

### Right Side

Hero pigeon imagery.

Large, elegant, realistic, premium.

Not illustrated.
Not cartoon.

Photographic realism preferred.

---

# 4. Color Palette

Primary palette should reflect:

## Deep Midnight Blue

Used for:

- hero backgrounds
- header/footer
- navigation bars

Represents:

- trust
- authority
- early race-morning atmosphere

Suggested:

```css
#0B1623
#102235
#1A2D42
```

---

## Warm Championship Gold

Used for:

- buttons
- accents
- icons
- dividers
- active highlights

Represents:

- trophies
- achievement
- prestige

Suggested:

```css
#C79A47
#D4A85A
#B98734
```

---

## Soft Ivory / Off White

Used for:

- typography
- card surfaces
- report backgrounds

Suggested:

```css
#F8F6F1
#F2EFE8
```

---

## Slate Grey

Supporting neutral.

Suggested:

```css
#4F5B66
```

---

# 5. Typography

## Display Typeface

Elegant serif for major titles.

Use for:

- hero title
- section headings
- leaderboard titles

Examples:

- Playfair Display
- Cormorant Garamond
- Libre Baskerville

Must feel:

- classic
- authoritative
- refined

---

## UI Typeface

Clean sans-serif for all functional text.

Examples:

- Inter
- Source Sans Pro
- Open Sans

Used for:

- tables
- menus
- filters
- buttons
- metadata

Must be highly readable on mobile.

---

# 6. Layout Principles

## Spacious

Use generous spacing.

Avoid clutter.

---

## Structured

Clear content zones:

- Hero
- Feature overview
- Supported federations/clubs
- Results access
- Leaderboards
- Footer

---

## Card-Based Information Design

All major information blocks should appear as elegant cards.

Cards should have:

- soft shadow
- subtle borders
- generous padding
- rounded corners

---

# 7. Iconography

Icons should be:

- minimal
- line-based
- refined

Use gold-accent monochrome styling.

Themes:

- trophies
- lofts
- race markers
- leaderboards
- mobile access
- reports

Avoid:

- generic clip-art
- thick cartoon icons

---

# 8. Tables / Result Presentation

This is where the platform becomes functional.

Result tables must feel:

## Official

Like reading an official race publication.

## Precise

Strong grid structure.

## Elegant

Subtle borders.

Clear hierarchy.

### Table styling

Header:

- dark blue background
- gold text / white text

Rows:

- alternating subtle striping

Sticky first column.

Horizontal mobile swipe.

Search/filter always visible.

---

# 9. Leaderboard Design

Leaderboards should feel prestigious.

Top rankings should visually feel important.

Consider:

### Rank styling

1st:
gold emphasis

2nd:
silver-grey

3rd:
bronze accent

Remaining:
clean neutral rows

This creates emotional sporting significance.

---

# 10. Mobile App Feel

The wrapped Capacitor app must feel like a premium native sports app.

Design should prioritise:

- thumb-friendly controls
- large tap zones
- clear hierarchy
- fast transitions
- minimal friction to reach results

Primary user journey:

```text
Open App
→ Select Federation
→ Select Result Type
→ View Results
```

Maximum 3 taps to core content.

---

# 11. Imagery Guidelines

Use photography that feels authentic to the sport.

Preferred:

- racing pigeons
- lofts
- sunrise race atmosphere
- release moments
- trophies
- club identity imagery

Avoid:

- stock business imagery
- artificial 3D renders
- generic SaaS illustrations

---

# 12. Branding Character

Vlugboek should feel like:

**The official record book of South African pigeon racing.**

The design should communicate:

- trust
- heritage
- precision
- prestige
- community

---

# 13. CodeX Implementation Guidance

Frontend implementation should use:

## React + Tailwind

Visual goals:

- premium dark hero sections
- elegant serif display typography
- gold-accent button system
- soft elevated cards
- highly readable result tables
- polished responsive transitions

The final experience should feel:

> premium sporting heritage meets modern mobile race-result access.
