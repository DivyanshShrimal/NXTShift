from PIL import Image

im = Image.open('roster_fixtures/roster_rot90.jpeg')
im.crop((0, 90, 1280, 160)).save('roster_fixtures/crop_oct04.jpeg')
print("Cropped oct04")
