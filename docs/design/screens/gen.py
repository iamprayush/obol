#!/usr/bin/env python3
"""Generates every Obol screen as a standalone 360x800 HTML file."""
import math, os, json

OUT = os.path.dirname(os.path.abspath(__file__))
CREAM, NAVY, GROUND = '#F6EBD9', '#0F1730', '#0A1022'

# Sky colour keyframes, by f = amount / morning cap.
KEYS = [
    (0.00, dict(bg='#0F1730', h1='#161F45', h2='#121A3A', sun='#FF7A2F')),
    (0.30, dict(bg='#2B1930', h1='#6E3038', h2='#47233A', sun='#FF7A2F')),
    (0.65, dict(bg='#7A2F2C', h1='#C2522E', h2='#9C3F2E', sun='#FF7A2F')),
    (1.00, dict(bg='#FFC27A', h1='#FF9A4A', h2='#FFAF63', sun='#FF5A1F')),
]

def _mix(a, b, t):
    a = [int(a[i:i+2], 16) for i in (1, 3, 5)]; b = [int(b[i:i+2], 16) for i in (1, 3, 5)]
    return '#%02X%02X%02X' % tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))

def stage(f):
    f = max(0.0, min(1.0, f))
    for (f0, c0), (f1, c1) in zip(KEYS, KEYS[1:]):
        if f <= f1:
            t = (f - f0) / (f1 - f0)
            c = {k: _mix(c0[k], c1[k], t) for k in c0}
            c['ink'] = NAVY if f >= 0.8 else CREAM
            return c

def geo(f):
    """Sun centre and radius while the meter runs."""
    return 560 - 190 * f, 130 + 25 * f

BLOB = "M0,-118 C68,-120 120,-66 118,2 C116,68 64,118 0,118 C-66,118 -118,68 -118,0 C-118,-66 -68,-116 0,-118 Z"
HORIZON = '<path d="M0,596 C80,578 150,608 230,594 C290,584 330,588 360,582 L360,800 L0,800 Z" fill="%s"/>' % GROUND
STARS = ('<g fill="#F6EBD9" opacity=".55"><circle cx="304" cy="96" r="1.5"/><circle cx="262" cy="150" r="1.1"/>'
         '<circle cx="326" cy="214" r="1.3"/><circle cx="236" cy="62" r="1"/></g>')

def scene(c, cy=None, r=118, halos=2, stars=False, ground=True, extra=''):
    s = '<svg class="scene" viewBox="0 0 360 800" xmlns="http://www.w3.org/2000/svg">'
    if stars: s += STARS
    if cy is not None:
        k = r / 118
        s += f'<g transform="translate(180 {cy:.0f})">'
        if halos >= 2: s += f'<path transform="scale({1.72*k:.3f})" d="{BLOB}" fill="{c["h2"]}"/>'
        if halos >= 1: s += f'<path transform="scale({1.34*k:.3f})" d="{BLOB}" fill="{c["h1"] if halos >= 2 else c["h2"]}"/>'
        s += f'<path transform="scale({k:.3f})" d="{BLOB}" fill="{c["sun"]}"/></g>'
    s += extra
    if ground: s += HORIZON
    return s + '</svg>'

def viewfinder(c, p, active=True, brush=True, cy=432):
    C = 2 * math.pi * 132
    ring = c['sun'] if active else 'rgba(246,235,217,.45)'
    s = f'<g transform="translate(180 {cy})">'
    s += '<circle r="118" fill="#070B18"/>'
    s += '<clipPath id="vf"><circle r="118"/></clipPath><g clip-path="url(#vf)" fill="#161D36">'
    s += '<circle cx="0" cy="-22" r="40"/><path d="M-92,130 C-92,58 -50,34 0,34 C50,34 92,58 92,130 Z"/></g>'
    if brush:
        s += '<path d="M14,-4 L64,10" stroke="#F6EBD9" stroke-opacity=".55" stroke-width="6" stroke-linecap="round"/>'
    s += '<circle r="132" fill="none" stroke="rgba(246,235,217,.14)" stroke-width="6"/>'
    if p > 0:
        s += (f'<circle r="132" fill="none" stroke="{ring}" stroke-width="6" stroke-linecap="round" '
              f'stroke-dasharray="{p*C:.1f} {C:.1f}" transform="rotate(-90)"/>')
    return s + '</g>'

# ---------- small HTML helpers ----------
def status(l, r=''): return f'<div class="abs status"><span>{l}</span><span>{r}</span></div>'
def quiet(t, top, extra=''): return f'<div class="abs quiet" style="top:{top}px;left:28px;right:28px;{extra}">{t}</div>'
def hero(t, size=150, top=118, extra=''): return f'<div class="abs hero" style="top:{top}px;font-size:{size}px;{extra}">{t}</div>'
def line(t, top=132, cls=''): return f'<div class="abs line {cls}" style="top:{top}px;">{t}</div>'
def primary(t, top=634): return f'<div class="pill primary" style="top:{top}px;">{t}</div>'
def secondary(t, top=706, extra=''): return f'<div class="pill secondary" style="top:{top}px;{extra}">{t}</div>'
def ghost(t, top=634): return f'<div class="pill ghost" style="top:{top}px;">{t}</div>'
def note(t, top=770): return f'<div class="note" style="top:{top}px;">{t}</div>'
def sunfig(cy, r, amt, lbl, size):
    top = cy - r; bottom = min(590, cy + r)
    return (f'<div class="sunfig" style="top:{top:.0f}px;height:{bottom-top:.0f}px;">'
            f'<div class="amt" style="font-size:{size:.0f}px;">{amt}</div><div class="lbl">{lbl}</div></div>')
def rows(top, h, items):
    s = f'<div class="rows" style="top:{top}px;">'
    for it in items: s += f'<div class="row" style="height:{h}px;">{it}</div>'
    return s + '</div>'
NAV = 'History · Settings'
SNOOZE = secondary('Snooze 5 min') + note('Quieter. Not cheaper.')

SCREENS = []  # (id, title, group, state note, html)
def add(sid, title, group, c, scn, body):
    html = ('<!DOCTYPE html><html><head><meta charset="utf-8"><title>%s</title><link rel="stylesheet" href="base.css"></head>'
            '<body><div class="screen" style="--bg:%s;--ink:%s;--sun:%s;">%s%s</div></body></html>') % (title, c['bg'], c['ink'], c['sun'], scn, body)
    SCREENS.append((sid, title, group))
    open(os.path.join(OUT, sid + '.html'), 'w').write(html)

def meter(sid, title, amount, st_l, st_r, label, text, fig_label, actions, cap=300, heat=None, group='Morning'):
    f = (heat if heat is not None else amount) / cap; c = stage(f); cy, r = geo(f)
    add(sid, title, group, c, scene(c, cy, r),
        status(st_l, st_r) + quiet(label, 104) + line(text) + sunfig(cy, r, f'₹{amount}', fig_label, 84 + 30 * f) + actions)

calm = stage(0)

# ---------- Setup ----------
add('01-welcome', 'Welcome', 'Setup', calm, scene(calm, 700, halos=1, stars=True),
    quiet("Hello. I'm", 104) + hero('obol', 140) + line('An alarm clock that bills<br>you for staying in bed.', 300)
    + quiet('5 free minutes after the alarm.<br>Then ₹10 a minute.<br>Two minutes of brushing stops it.', 388)
    + primary('Set me up') + note("The first week is a trial. Nothing's charged.", 712))

add('02-permissions', 'Permissions', 'Setup', calm, scene(calm, 700, halos=1),
    quiet('Before we start', 104) + line('I need four things. None<br>of them are your contacts.')
    + rows(232, 64, ['<span class="l">Alarms</span><span class="m" style="text-align:right;">Allowed</span>',
                     '<span class="l">Camera</span><span class="chip">Allow</span>',
                     '<span class="l">Notifications</span><span class="chip">Allow</span>',
                     '<span class="l">Battery, unrestricted</span><span class="chip">Allow</span>'])
    + ghost('Continue') + note('3 to go.', 712))

def alarm_row(day, t, on=True, cap=''):
    capt = f'<br><span style="font-size:12px;opacity:.6;">{cap}</span>' if cap else ''
    dim = '' if on else 'opacity:.35;'
    return (f'<span class="l" style="line-height:1.25;">{day}{capt}</span><span class="t" style="{dim}">{t}</span>'
            f'<span class="toggle {"on" if on else ""}"><i></i></span>')
add('03-alarms', 'Alarms', 'Setup', calm, scene(calm, ground=False),
    status('‹ Home') + line("Moving one later takes<br>a day. I've met you.", 96)
    + rows(184, 58, [alarm_row('Monday', '6:10'), alarm_row('Tuesday', '6:10'),
                     alarm_row('Wednesday', '6:10', cap='7:00 after tomorrow'), alarm_row('Thursday', '6:10'),
                     alarm_row('Friday', '6:10'), alarm_row('Saturday', '8:00'), alarm_row('Sunday', '8:00', on=False)])
    + quiet('Later or off starts in 24 hours.<br>Earlier starts right away.', 614))

# ---------- Night (home) ----------
def home(sid, title, label, big, text, sub, actions, size=150):
    add(sid, title, 'Night', calm, scene(calm, 700, halos=1, stars=True),
        status('Tue 22:47', NAV) + quiet(label, 104) + hero(big, size) + line(text, 300) + sub + actions)

home('04-home', 'Home', 'Tomorrow, Wednesday', '6:10', "Sleep well. I'm rooting<br>for the snooze button.",
     quiet('5 free minutes, then ₹10 a minute.', 384),
     secondary('<span>Take a night off</span><span style="opacity:.55;font-size:14px;">2 left</span>', extra='justify-content:space-between;padding:0 24px;')
     + note('<span class="link">Change alarms</span>'))

home('05-home-unpaid', 'Home, unpaid', 'Tomorrow, Wednesday', '6:10', "Sleep well. I'm rooting<br>for the snooze button.",
     '<div class="abs" style="top:446px;left:28px;right:28px;"><div class="serif" style="font-size:28px;line-height:1;">'
     '<span style="color:var(--sun);">₹80</span> unpaid</div><div class="quiet" style="margin-top:10px;">'
     "I'm holding tomorrow's free minutes<br>as collateral.</div></div>",
     primary('Pay ₹80')
     + secondary('<span>Take a night off</span><span style="opacity:.55;font-size:14px;">2 left</span>', extra='justify-content:space-between;padding:0 24px;')
     + note('<span class="link">Change alarms</span>'))

home('06-home-night-off', 'Home, night off', 'Tomorrow, Wednesday', '6:10', "Tomorrow's free. I'll just<br>sit here, then.",
     quiet("The alarm still rings. Nothing's charged.", 384),
     '<div class="pill ghost" style="top:706px;height:54px;font-size:16px;justify-content:space-between;padding:0 24px;">'
     '<span>Night off taken</span><span style="font-size:14px;">1 left</span></div>' + note('<span class="link">Change alarms</span>'))

home('07-home-paused', 'Home, paused', 'Paused since Mon 16 Nov', 'Off.', "No alarms. No charges.<br>I'm not sulking.",
     '', primary('Resume') + note('Resuming is instant. Pausing took 12 hours.', 712))

# ---------- Morning ----------
c = calm
add('08-alarm-free', 'Alarm, free minutes', 'Morning', c, scene(c, 606),
    status('Wed 06:11', 'ringing since 6:10') + quiet('Free for', 104) + hero('4:12') + quiet('then ₹10 a minute.', 278)
    + line("Take your time.<br>It's only money.", 340) + sunfig(606, 118, '₹0', 'so far', 30) + primary("I'm up") + SNOOZE)

meter('09-meter', 'Meter running', 10, 'Wed 06:16', 'ringing since 6:10', 'Meter running. ₹10 a minute.',
      'No rush.<br>I bill by the minute.', 'so far', primary("I'm up") + SNOOZE)

meter('09b-meter-no-free', 'Meter running, no free minutes', 10, 'Wed 06:11', 'ringing since 6:10', 'No free minutes. ₹80 still unpaid.',
      'No warm-up today.<br>You know why.', 'so far', primary("I'm up") + SNOOZE)

f = 50 / 300; c = stage(f); cy, r = geo(f)
add('10-snoozed', 'Snoozed', 'Morning', c, scene(c, cy, r),
    status('Wed 06:20', 'rings again at 6:23') + quiet('Quiet for', 104) + hero('3:00')
    + line("Enjoy the silence.<br>I'm still counting.", 296) + sunfig(cy, r, '₹50', 'so far', 84 + 30 * f)
    + primary("I'm up") + note("The meter doesn't snooze.", 712))

meter('11-ring-again', 'Rings again', 80, 'Wed 06:23', 'ringing since 6:10', 'Meter running. ₹10 a minute.',
      'Welcome back.<br>That nap cost ₹50.', 'so far', primary("I'm up") + SNOOZE)

meter('12-capped', 'Capped', 300, 'Wed 06:45', 'ringing since 6:10', 'Charging has stopped. Ringing has not.',
      "That's the cap. I'd take<br>more, but rules.", 'capped',
      primary("I'm up") + secondary('Snooze 5 min') + note('Rings until 7:45. Then ₹300 carries to tomorrow.'))

meter('13-trial', 'Trial week', 80, 'Wed 06:23', 'trial, day 3 of 7', "Trial week. Nothing's charged.",
      "This one's on the house.<br>I'm practising.", 'pretend', primary("I'm up") + SNOOZE)

# ---------- Brushing ----------
def camera(sid, title, group, f, st_l, st_r, label, big, p, active, brush, dot_on, msg, text, link='', dim=False):
    c = stage(f)
    add(sid, title, group, c, scene(c, extra=viewfinder(c, p, active, brush)),
        status(st_l, st_r) + quiet(label, 104) + hero(big, extra='opacity:.4;' if dim else '')
        + f'<div class="abs g" style="top:612px;left:28px;right:20px;font-size:15px;font-weight:600;">'
          f'<span class="dot {"" if dot_on else "off"}"></span>{msg}</div>'
        + f'<div class="abs line small g" style="top:650px;">{text}</div>' + (note(link) if link else ''))

camera('14-brushing', 'Brushing, seen', 'Brushing', 100 / 300, 'Wed 06:26', 'meter paused', 'Keep brushing for', '1:12', 0.4, True, True, True,
       'Brushing seen. Meter paused at ₹100.', 'Two minutes. Your dentist<br>and I agree on this.')
camera('15-brushing-lost', 'Brushing, not seen', 'Brushing', 103 / 300, 'Wed 06:26', 'meter running', 'Brushing left', '1:12', 0.4, False, False, False,
       "Can't see a toothbrush. ₹103 so far.", "I don't see a brush.<br>I do see ₹10 a minute.",
       '<span class="link">Camera not working?</span>', dim=True)

c = stage(140 / 300)
add('16-override', 'Camera not working', 'Brushing', c, scene(c),
    status('Wed 06:31', '₹140 so far') + quiet('Camera not working', 104) + line('Fine. Type this,<br>word for word.')
    + '<div class="box serif" style="top:224px;border:1.5px solid rgba(246,235,217,.38);">'
      'I am awake, upright, and paying twenty rupees for the privilege.</div>'
    + '<div class="box" style="top:366px;background:rgba(10,16,34,.45);font-size:17px;min-height:96px;">'
      'I am awake, upright, and pay<span style="display:inline-block;width:2px;height:20px;background:var(--sun);vertical-align:-3px;margin-left:1px;"></span></div>'
    + ghost('Stop the meter for ₹20') + note('The face check still happens.', 712))

# ---------- After ----------
f = 100 / 300; c = stage(f); cy, r = geo(f)
add('17-pay', 'Pay', 'After', c, scene(c, cy, r),
    status('Wed 06:27', 'brushed 6:25 to 6:27') + quiet("Meter's off.", 104) + line('Pleasure doing<br>business.')
    + sunfig(cy, r, '₹100', 'final', 84 + 30 * f) + primary('Pay ₹100') + secondary('Later')
    + note("Later costs you tomorrow's free minutes."))

add('17b-pay-failed', 'Payment failed', 'After', c, scene(c, cy, r),
    status('Wed 06:28', 'brushed 6:25 to 6:27') + quiet("Payment didn't go through.", 104) + line("Nothing was taken.<br>I'll wait.")
    + sunfig(cy, r, '₹100', 'still owed', 84 + 30 * f) + primary('Try again') + secondary('Later')
    + note("Later costs you tomorrow's free minutes."))

add('18-nothing-owed', 'Nothing owed', 'After', calm, scene(calm, 606),
    status('Wed 06:16', 'brushed 6:14 to 6:16') + quiet('The meter never started.', 104)
    + line('Nothing owed.<br>Well done, I suppose.') + sunfig(606, 118, '₹0', 'final', 30)
    + primary('Done') + note('Face check at 6:36.', 712))

add('19-face-check-wait', 'Face check, waiting', 'After', calm, scene(calm, 606),
    status('Wed 06:28', NAV) + quiet('Face check in', 104) + hero('19:42', 122, 128)
    + line("Going back to bed?<br>I'll know.", 300) + sunfig(606, 118, '₹100', 'paid', 30)
    + ghost('Do it early, from 6:37') + note('Rings at 6:47.', 712))

add('20-face-check', 'Face check', 'After', calm, scene(calm, extra=viewfinder(calm, 0, True, False)),
    status('Wed 06:47', 'face check') + quiet('Show your face within', 104) + hero('4:31')
    + '<div class="abs g" style="top:612px;left:28px;right:20px;font-size:15px;font-weight:600;">'
      '<span class="dot off"></span>Looking for a face. Lights on, please.</div>'
    + '<div class="abs line small g" style="top:650px;">Just checking you\'re<br>still vertical.</div>')

meter('21-face-check-missed', 'Face check, missed', 30, 'Wed 06:55', 'face check missed at 6:52', 'Meter running. ₹10 a minute.',
      'Back in bed? Bold.<br>Expensive, but bold.', 'more, so far', primary('Show my face') + note('Then one more check, 20 minutes later.', 712),
      heat=130, group='After')

def closed(sid, title, label, big, text, sub, actions):
    add(sid, title, 'After', calm, scene(calm, 700, halos=1),
        status('Wed 06:47', NAV) + quiet(label, 104) + hero(big, 132, 124) + line(text, 300) + quiet(sub, 384) + actions)
closed('22-closed', 'Morning closed', 'This morning cost', '₹100', '₹100 poorer,<br>but vertical.', 'Up in 15 minutes. Paid at 6:28.',
       primary('Done') + secondary('See the full bill'))
closed('23-closed-free', 'Morning closed, free', 'This morning cost', '₹0', "A free morning.<br>I'll survive.", 'Up in 4 minutes.',
       primary('Done'))
closed('24-not-rung', "Alarm didn't ring", 'This morning cost', '₹0', "The alarm didn't ring.<br>That one's on me.", 'Morning cancelled. Nothing charged.',
       primary('Done'))

# ---------- Daylight ----------
def hrow(d, m, a): return f'<span class="l" style="width:56px;">{d}</span><span class="m" style="margin-left:0;">{m}</span><span class="r">{a}</span>'
add('25-history', 'History', 'Daylight', calm, scene(calm, ground=False),
    status('‹ Home') + quiet('November', 104) + hero('₹340', 132, 124) + quiet('of the ₹3,000 monthly cap', 268)
    + line('My best month so far.', 306)
    + rows(366, 52, [hrow('Sat 21', 'up in 3 min', '<span style="opacity:.5;">free</span>'), hrow('Fri 20', 'up in 16 min', '₹110'),
                     hrow('Thu 19', 'night off', '<span style="opacity:.5;">free</span>'), hrow('Wed 18', 'up in 15 min', '₹100'),
                     hrow('Tue 17', 'up in 13 min', '₹80'), hrow('Mon 16', 'up in 10 min', '₹50')])
    + quiet('Earlier', 700, 'text-align:center;'))

def drow(t, e, a=''): return f'<span class="l" style="width:44px;opacity:.6;">{t}</span><span class="m" style="margin-left:0;opacity:1;">{e}</span><span class="r">{a}</span>'
add('26-bill', 'The full bill', 'Daylight', calm, scene(calm, ground=False),
    status('‹ November') + quiet('Wed 18 Nov', 104) + hero('₹100', 132, 124) + line('Every rupee, itemised.', 272)
    + rows(326, 46, [drow('6:10', 'Alarm rang'), drow('6:13', 'Snoozed'), drow('6:15', 'Free minutes over. Meter on', '₹0'),
                     drow('6:18', 'Rang again. Snoozed', '₹30'), drow('6:23', 'Rang again', '₹80'),
                     drow('6:25', 'Brushing seen. Meter paused', '₹100'), drow('6:27', 'Two minutes done. Meter off', '₹100'),
                     drow('6:28', 'Paid', '₹100'), drow('6:47', 'Face check passed')]))

def srow(k, v, hot=False): return f'<span class="l">{k}</span><span class="m" style="text-align:right;{"color:var(--sun);opacity:1;" if hot else ""}">{v}</span>'
add('27-settings', 'Settings', 'Daylight', calm, scene(calm, ground=False),
    status('‹ Home') + line('Settings', 92)
    + rows(142, 54, [srow('Rate', '₹10 a minute'), srow('Free minutes', '5'), srow('Morning cap', '₹300'), srow('Monthly cap', '₹3,000'),
                     srow('Brushing', '2 minutes'), srow('Face check', '20 minutes after'), srow('Money goes to', 'Not set', True),
                     srow('Trial week', 'Finished'),
                     '<span class="l">Pause</span><span class="toggle" style="margin-left:auto;"><i></i></span>'])
    + quiet('Pausing starts 12 hours after you ask. Nice try.', 642, 'font-size:13px;'))

# ---------- Escalation strip (spec only) ----------
ESC = []
for amt in (0, 60, 150, 240, 300):
    f = amt / 300; c = stage(f); cy, r = (606, 118) if amt == 0 else geo(f)
    sid = f'esc-{amt:03d}'
    html = ('<!DOCTYPE html><html><head><meta charset="utf-8"><link rel="stylesheet" href="base.css"></head>'
            '<body><div class="screen" style="--bg:%s;--ink:%s;--sun:%s;">%s%s</div></body></html>') % (
            c['bg'], c['ink'], c['sun'], scene(c, cy, r), sunfig(cy, r, f'₹{amt}', 'so far', 30 if amt == 0 else 84 + 30 * f))
    open(os.path.join(OUT, sid + '.html'), 'w').write(html); ESC.append(sid)

json.dump({'screens': SCREENS, 'esc': ESC, 'stages': {str(a): stage(a / 300) for a in (0, 60, 90, 150, 195, 240, 300)}},
          open(os.path.join(OUT, 'manifest.json'), 'w'), indent=1, ensure_ascii=False)
print(len(SCREENS), 'screens')
