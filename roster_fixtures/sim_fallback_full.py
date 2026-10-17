tableLeft = 0
tableRight = 1280
width = tableRight - tableLeft

headers = [
    ("Date", 0.08),
    ("9 AM - 5 PM", 0.11),
    ("10 AM - 6 PM", 0.11),
    ("12 PM - 8 PM", 0.11),
    ("8 AM - 2 PM", 0.14),
    ("2 PM - 8 PM", 0.14),
    ("8 PM - 8 AM", 0.14),
    ("Off", 0.10),
    ("Leave", 0.07)
]

currX = tableLeft
cols = []
for i in range(len(headers)):
    name, frac = headers[i]
    minX = int(currX)
    currX += frac * width
    maxX = 999999 if i == len(headers) - 1 else int(currX)
    cols.append((i, name, minX, maxX))

for c in cols:
    print(f"Col {c[0]} '{c[1]}': [{c[2]} .. {c[3]}]")

elements = [
    ("Date 02-Oct", 65),
    ("Kuldeep", 422),
    ("Pradeep", 480),
    ("Vishal", 535),
    ("Ajay Jangid", 610),
    ("Yamini", 670),
    ("Govind", 760),
    ("Rohit", 800),
    ("Shiv (Off)", 880),
    ("Munesh (Off)", 940)
]

print("\n--- Fallback Columns Assignment (tableLeft=0, tableRight=1280) ---")
for el, cx in elements:
    matched = None
    for idx, name, minX, maxX in cols:
        if cx >= minX and cx < maxX:
            matched = (idx, name)
            break
    print(f"{el} (cx={cx}) -> {matched}")
