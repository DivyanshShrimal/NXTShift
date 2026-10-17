from PIL import Image
import numpy as np

# Load oct02 row crop: y from 72 to 92 in roster_rot90.jpeg, x from 550 to 850
im = Image.open('roster_fixtures/roster_rot90.jpeg').convert('L')
arr = np.array(im)

# Let's inspect x from 550 to 850 at y=76..86
row_crop = arr[74:86, 560:850]
# Find text ink (low values)
ink_x = np.mean(row_crop < 140, axis=0)

# Print columns where ink is detected
for i in range(len(ink_x)):
    x = 560 + i
    if ink_x[i] > 0.15:
        print(f"Ink at x={x}")
