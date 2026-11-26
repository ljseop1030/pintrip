---
name: ui-refactor
description: Apply the UI v2 Figma design to one screen (or one part of a screen) of PinTrip. Use when the user names a Figma frame (e.g. "06 내 여행방") and a mode (style / behavior / verify).
---

# UI refactor

The user gives: **frame** (e.g. "06 내 여행방"), optional **part** (e.g. "header"), and **mode**: `style`, `behavior`, or `verify`.

## Always
1. Read DESIGN.md and AGENTS.md.
2. Find the frame's files in DESIGN.md "Frame ↔ file". Work only on those files (plus shared values/drawables when needed).
3. If a part is given, change only that part.
4. If the Figma MCP is unavailable, stop and ask.

## Mode: style
- Get the frame from the Figma MCP and match it.
- Change looks only. Keep view IDs, Kotlin code and screen order.
- Use existing shared styles first (Widget.Pintrip.*, TextAppearance.Pintrip.*, ShapeAppearance.Pintrip.*, colors, drawables). Change an existing color's value before adding a new one.
- Views not in Figma (loading, error, empty, failure cards) stay; match their style to the screen.
- Build, run on the emulator, check Logcat.

## Mode: behavior
- Do only the DESIGN.md "Behavior" item(s) for this screen that the user names.
- If something it depends on does not exist yet, connect what you can and leave a TODO.
- Build, run, check Logcat.

## Mode: verify
- The user attaches the Figma image and an app screenshot.
- List every visible difference (spacing, size, color, radius, shadow, icon, text), then fix them.

## Report when done
- Files changed
- Files created (and why no existing file could be used)
- Colors whose values changed, and other screens that use them
- Anything you could not match to Figma
- TODOs left