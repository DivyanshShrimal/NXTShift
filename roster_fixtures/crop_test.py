from PIL import Image

im = Image.open('roster_fixtures/roster_rot90.jpeg')
# Let's crop:
# Header: top 15%
im.crop((0, 0, 1280, 120)).save('roster_fixtures/crop_header.jpeg')
# 02-Oct row: roughly around y=60 to 120
im.crop((0, 50, 1280, 120)).save('roster_fixtures/crop_oct02.jpeg')
print("Cropped header and oct02")
