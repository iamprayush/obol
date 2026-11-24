# Obol — design spec

This is the visual and copy spec for building Obol's screens. It covers 29 screens across the whole flow in [obol-user-flow.md](obol-user-flow.md), the rule that drives the sun and sky, and the tokens.

Each screen is a standalone 360×800 HTML file in [obol-design/screens](screens), with a 2× PNG in [obol-design/png](png). The same folder is zipped as [obol-design.zip](obol-design.zip).

## Three rules

1. **One number per screen.** The number that matters right now is the largest thing on the screen: the alarm time, the free time left, or the money.
2. **One sun.** The sun is the meter. It is the only illustration. It rises and the sky heats up as the amount grows.
3. **One dry line per screen, at most.** The app is polite on the surface and lets the user work out the insult. Everything else is plain.

## Screens

The number is the file name prefix, for example `08-alarm-free.html`.

### Setup

![Setup](boards/board-1-setup.png)

| Screen | Shows when |
|---|---|
| 01 Welcome | First launch |
| 02 Permissions | After Welcome; Continue unlocks when all four are allowed |
| 03 Alarms | Last setup step, and from "Change alarms" on Home |

### Night

![Night](boards/board-2-night.png)

| Screen | Shows when |
|---|---|
| 04 Home | Default, whenever no morning is in progress |
| 05 Home, unpaid | A toll is unpaid |
| 06 Home, night off | A night off is taken for tomorrow |
| 07 Home, paused | Pause is active |

### Morning

![Morning](boards/board-3-morning.png)

| Screen | Shows when |
|---|---|
| 08 Alarm, free minutes | Alarm rings and nothing is unpaid |
| 09 Meter running | Free minutes are over |
| 09b Meter running, no free minutes | Alarm rings with a toll unpaid |
| 10 Snoozed | During the five silent minutes |
| 11 Rings again | Snooze ends; the line states what the nap cost |
| 12 Capped | Amount reaches the morning cap |
| 13 Trial week | Any meter screen during the trial; the sun label reads "pretend" |

### Brushing

![Brushing](boards/board-4-brushing.png)

| Screen | Shows when |
|---|---|
| 14 Brushing, seen | Camera sees brushing; the ring fills over two minutes |
| 15 Brushing, not seen | Brushing unseen for 10 seconds; meter runs again |
| 16 Camera not working | Camera has failed for 60 seconds and the user taps the link |

### After

![After](boards/board-5-after.png)

| Screen | Shows when |
|---|---|
| 17 Pay | Two minutes done and money is owed |
| 17b Payment failed | Payment did not complete |
| 18 Nothing owed | Two minutes done and nothing is owed |
| 19 Face check, waiting | Until the face check rings |
| 20 Face check | Face check rings, or the user does it early |
| 21 Face check, missed | Five minutes pass with no face |
| 22 Morning closed | Face check passed, money was charged |
| 23 Morning closed, free | Face check passed, nothing charged |
| 24 Alarm didn't ring | The alarm never fired |

### Daylight

![Daylight](boards/board-6-daylight.png)

| Screen | Shows when |
|---|---|
| 25 History | From Home |
| 26 The full bill | From a History row or "See the full bill" |
| 27 Settings | From Home |

## The sun and the sky

![Escalation](boards/board-7-escalation.png)

All values are in dp on the 360×800 canvas.

```
f         = this morning's total ÷ morning cap      (0 to 1)
centre y  = 560 − 190·f
radius    = 130 + 25·f
figure    = 84 + 30·f      (size of the amount on the sun)
halos     = two rings at 1.34× and 1.72× the radius
```

- **Free minutes, and any time the meter is off:** centre y 606, radius 118, figure 30.
- **Home and morning-closed screens:** centre y 700, radius 118, one ring, no figure.
- **Horizon:** a fixed curve at about y 590. Everything below it is ground, and the buttons always sit there.
- **Brushing screens:** the sky keeps the colour for the current f, and the camera circle takes the sun's place.

The sky colour is interpolated between these keyframes:

| f | Sky | Inner ring | Outer ring | Sun | Text |
|---|---|---|---|---|---|
| 0 | `#0F1730` | `#161F45` | `#121A3A` | `#FF7A2F` | cream |
| 0.30 | `#2B1930` | `#6E3038` | `#47233A` | `#FF7A2F` | cream |
| 0.65 | `#7A2F2C` | `#C2522E` | `#9C3F2E` | `#FF7A2F` | cream |
| 1.00 | `#FFC27A` | `#FF9A4A` | `#FFAF63` | `#FF5A1F` | navy from f ≥ 0.8 |

The sky is hot only while the meter is running, or has just stopped and is unpaid (screens 09 to 17b, and 21). Every other screen uses f = 0.

## Tokens

**Colour**

| Name | Value | Used for |
|---|---|---|
| Cream | `#F6EBD9` | Text, primary button |
| Navy | `#0F1730` | Night sky, text on the sun and on the primary button |
| Ground | `#0A1022` | Below the horizon |
| Sun | `#FF7A2F` | The sun, unpaid amounts, switches that are on |

Quiet text is cream at 62%, status text 55%, notes 60%, row lines 12%, secondary button outline 38%. A disabled button is a 10% cream fill with 50% text.

**Type**

Two families: Fraunces (variable, with `SOFT` 100 and `WONK` 1) and Instrument Sans.

| Role | Font | Size | Weight |
|---|---|---|---|
| Hero number | Fraunces italic | 150 (132 for rupee amounts, 122 for five characters) | 300, tracking −0.035em |
| Dry line | Fraunces italic | 26 (21 on the ground) | 400, line height 1.22 |
| Amount on the sun | Fraunces italic | 84 to 114, or 30 | 400 |
| Alarm time in a list | Fraunces italic | 28 | 300 |
| Amount in a row | Fraunces | 19 | 400 |
| Label, body | Instrument Sans | 15 | 500 |
| Status, note | Instrument Sans | 13 | 500 |
| Primary button | Instrument Sans | 19 | 600 |
| Secondary button | Instrument Sans | 16 | 500 |

**Layout**

| Element | Position |
|---|---|
| Side margin | 28 |
| Status row | y 20 |
| Label | y 104 |
| Hero number | y 118 |
| Dry line | about y 300 under a hero (y 340 on screen 08), y 132 without one |
| Primary button | y 634, height 60, full pill, width 304 |
| Secondary button | y 706, height 54 |
| Note | y 770, or y 712 when there is no secondary button |
| List rows | 46 to 64 high, 1dp line between |

## Copy

- The app speaks as "I".
- Short sentences with full stops. No exclamation marks, no emoji, no capitals for emphasis.
- The joke is never in a button label; buttons say what they do.
- When the app is at fault, it says so plainly ("That one's on me.").

Strings that are not drawn:

| Where | Text |
|---|---|
| 15, no face | "Can't see a face. ₹N so far." |
| 15, brush away from mouth | "The brush isn't at your mouth. ₹N so far." |
| 15, too dark | "Too dark. Turn on a light. ₹N so far." |
| 11, first nap of the worked morning | "Welcome back. That nap cost ₹30." |
| 04, pause requested but not yet active | "Pause starts at 10:47 tomorrow." |

## Not drawn or still open

1. **Notifications** are not designed.
2. **Motion** is not specified. The sun and sky should move continuously with the meter; that is the only intent recorded here.
3. **Night off:** the mock treats it as final once taken, because the flow does not say whether it can be undone.
4. **Payment:** Settings shows "Money goes to: Not set" because the payment rail is undecided. A payment that is submitted but unconfirmed has no screen.
5. **Sink sticker:** still undecided, so it has no screen.
6. **Capped and never brushed:** when the ringing stops at 7:45, the app goes to screen 05 with ₹300 unpaid.

## Checks

All 29 screens were rendered in headless Chrome at 360×800 and reviewed by eye for overlaps and clipping. Nothing has been checked on a phone. The rupee sign renders in both fonts.

To change a screen, edit `gen.py` in the screens folder and run `render.sh`; it rebuilds every HTML file, PNG and board.
