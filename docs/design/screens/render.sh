#!/usr/bin/env bash
# Renders every screen to png/ (2x) and builds one review board per group in boards/.
cd "$(dirname "$0")" || exit 1
python3 gen.py || exit 1
rm -rf png boards board-*.html; mkdir -p png boards
shot() { # out, url, width, height, scale
  timeout 30 google-chrome --headless=new --no-sandbox --disable-gpu --user-data-dir=/tmp/obol-chrome-r --hide-scrollbars \
    --force-device-scale-factor="$5" --window-size="$3,$4" --virtual-time-budget=1500 --screenshot="$1" "$2" >/dev/null 2>&1 || echo "FAIL $1"
}
for f in [0-9][0-9]*-*.html esc-*.html; do shot "$PWD/png/${f%.html}.png" "file://$PWD/$f" 360 800 2; done
python3 - <<'EOF' > boards.txt
import json
m = json.load(open('manifest.json'))
groups = {}
for i, t, g in m['screens']: groups.setdefault(g, []).append((i, t))
groups['Escalation'] = [(e, '₹%d' % int(e[4:])) for e in m['esc']]
for n, (g, items) in enumerate(groups.items(), 1):
    per = 5 if g == 'Escalation' else 4
    rows = -(-len(items) // per); cols = min(per, len(items))
    cells = ''.join(f'<figure><img src="png/{i}.png" width="360" height="800"><figcaption>{i.split("-")[0] if g != "Escalation" else ""} {t}</figcaption></figure>' for i, t in items)
    name = f'board-{n}-{g.lower()}'
    open(name + '.html', 'w').write(
        '<!DOCTYPE html><html><head><meta charset="utf-8"><style>body{margin:0;background:#05070F;display:flex;flex-wrap:wrap;gap:24px;padding:24px;'
        f'width:{cols*384+24}px;box-sizing:border-box;font:500 16px sans-serif;color:#aab}}figure{{margin:0}}figcaption{{padding-top:10px;height:26px}}img{{display:block;border-radius:18px}}</style></head>'
        f'<body>{cells}</body></html>')
    print(name, cols * 384 + 24, rows * 860 + 24)
EOF
while read -r name w h; do shot "$PWD/boards/$name.png" "file://$PWD/$name.html" "$w" "$h" 1; done < boards.txt
ls png | wc -l; ls boards
