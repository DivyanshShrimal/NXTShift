from PIL import Image
im = Image.open('roster_fixtures/october_2026_roster.jpeg')
im.rotate(90, expand=True).save('roster_fixtures/roster_rot90.jpeg')
im.rotate(270, expand=True).save('roster_fixtures/roster_rot270.jpeg')
print('Rotated successfully')
