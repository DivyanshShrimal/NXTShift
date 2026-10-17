# Header boxes:
# Let's see what header bounding boxes exist in the image:
# Header text & approximate boxes:
# 1. "Month" - wait! Is "Month" in headerCandidates?
# In FullMonthRosterParser:
# "parseShiftTiming(up) != null || up.contains('OFF') || up.contains('LEAVE') || up.contains('DATE') || up.contains('DAY') || up.contains('MORN') || up.contains('EVEN') || up.contains('NIGHT') || up.contains('GEN')"
# Does "MONTH" match any of those?
# "MONTH" contains NONE of those!
# So "Month" was NOT IN headerCandidates!

# What headers ARE in headerCandidates?
# 1. "9am - 5pm": bounds [95, 45, 165, 55] (left=95, right=165)
# 2. "10am - 6pm": bounds [335, 45, 395, 55] (left=335, right=395)
# 3. "12pm-8pm": bounds [395, 45, 455, 55] (left=395, right=455)
# 4. "8am - 2pm": bounds [455, 45, 520, 55] (left=455, right=520)
# 5. "2pm - 8pm": bounds [580, 45, 645, 55] (left=580, right=645)
# 6. "8pm - 8am": bounds [735, 45, 800, 55] (left=735, right=800)
# 7. "Off": bounds [840, 45, 865, 55] (left=840, right=865)
# 8. "Leave": bounds [980, 45, 1020, 55] (left=980, right=1020)

headers = [
    ("9am - 5pm", 95, 165),
    ("10am - 6pm", 335, 395),
    ("12pm-8pm", 395, 455),
    ("8am - 2pm", 455, 520),
    ("2pm - 8pm", 580, 645),
    ("8pm - 8am", 735, 800),
    ("Off", 840, 865),
    ("Leave", 980, 1020)
]

# Calculate minX and maxX according to FullMonthRosterParser:
cols = []
for i in range(len(headers)):
    name, left, right = headers[i]
    prev_right = headers[i-1][2] if i > 0 else 0
    curr_left = left
    curr_right = right
    next_left = headers[i+1][1] if i < len(headers) - 1 else 999999
    
    minX = 0 if i == 0 else (prev_right + curr_left) // 2
    maxX = 999999 if i == len(headers) - 1 else (curr_right + next_left) // 2
    cols.append((name, minX, maxX))

for c in cols:
    print(f"Column '{c[0]}': minX={c[1]}, maxX={c[2]}")

# Now check where October 2 elements fall:
# 1. Kuldeep in 12pm-8pm: x in [395, 450], center ≈ 422
# 2. Pradeep, Vishal in 8am-2pm: x in [455, 560], center of Pradeep ≈ 480, Vishal ≈ 535
# 3. Ajay Jangid, Yamini in 2pm-8pm: x in [580, 710]
#    Ajay Jangid: x in [580, 640], center ≈ 610
#    Yamini: x in [650, 705], center ≈ 677!
# 4. Govind, Rohit in 8pm-8am: x in [735, 820]
#    Govind: x in [735, 775], center ≈ 755
#    Rohit: x in [780, 820], center ≈ 800
# 5. Ajay Jindoliya, Shiv, Munesh in Off: x in [840, 975]

elements = [
    ("Kuldeep", 422),
    ("Pradeep", 480),
    ("Vishal", 535),
    ("Ajay Jangid", 610),
    ("Yamini", 677),
    ("Govind", 755),
    ("Rohit", 800),
    ("Shiv (Off)", 900)
]

print("\n--- Element Column Placement ---")
for el, cx in elements:
    matched = None
    for name, minX, maxX in cols:
        if cx >= minX and cx < maxX:
            matched = name
            break
    print(f"{el} (cx={cx}) -> Column '{matched}'")
