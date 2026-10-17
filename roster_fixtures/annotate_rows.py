from PIL import Image, ImageDraw

im = Image.open('roster_fixtures/roster_rot90.jpeg')
# Crop y from 30 to 200, x from 30 to 1150
crop = im.crop((30, 30, 1150, 200))
draw = ImageDraw.Draw(crop)

# Draw horizontal lines every 10 pixels
for y in range(0, 170, 10):
    color = "red" if y % 20 == 0 else "blue"
    draw.line([(0, y), (1120, y)], fill=color, width=1)
    draw.text((5, y), str(30 + y), fill="red")

crop.save('roster_fixtures/grid_rows_annotated.jpeg')
print("Annotated rows")
