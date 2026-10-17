from PIL import Image
import numpy as np

im = Image.open('roster_fixtures/roster_rot90.jpeg')
w, h = im.size
print(f"Image dimensions: {w} x {h}")

# Let's inspect the header row y range
# In a 1600x900 or whatever image:
print(f"Width={w}, Height={h}")
