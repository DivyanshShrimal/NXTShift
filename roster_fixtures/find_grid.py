from PIL import Image
import numpy as np

im = Image.open('roster_fixtures/roster_rot90.jpeg').convert('L')
arr = np.array(im)
h, w = arr.shape

# Let's crop the table area
# Find dark vertical lines
# In the table region, say rows 50 to 600
table_rows = arr[40:630, :]
# Dark lines have low intensity
# Average along column axis in table_rows
col_intensity = np.mean(table_rows, axis=0)

# Also let's find row lines
row_intensity = np.mean(arr[:, 60:1100], axis=1)

# Find local minima in col_intensity (vertical lines)
vertical_lines = []
for x in range(1, w - 1):
    if col_intensity[x] < 140 and col_intensity[x] <= col_intensity[x-1] and col_intensity[x] <= col_intensity[x+1]:
        if not vertical_lines or (x - vertical_lines[-1]) > 15:
            vertical_lines.append(x)

print(f"Candidate vertical grid lines ({len(vertical_lines)}): {vertical_lines}")

# Find local minima in row_intensity (horizontal lines)
horizontal_lines = []
for y in range(1, h - 1):
    if row_intensity[y] < 150 and row_intensity[y] <= row_intensity[y-1] and row_intensity[y] <= row_intensity[y+1]:
        if not horizontal_lines or (y - horizontal_lines[-1]) > 10:
            horizontal_lines.append(y)

print(f"Candidate horizontal grid lines ({len(horizontal_lines)}): {horizontal_lines}")
