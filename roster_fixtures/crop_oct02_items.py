from PIL import Image

im = Image.open('roster_fixtures/roster_rot90.jpeg')
# Row 02-Oct is at y ≈ 62 to 82
# Let's crop:
# 1. Date: 40-95
im.crop((40, 62, 95, 82)).save('roster_fixtures/oct02_date.jpeg')
# 2. Kuldeep: 395-455
im.crop((395, 62, 455, 82)).save('roster_fixtures/oct02_kuldeep.jpeg')
# 3. Pradeep, Vishal: 455-580
im.crop((455, 62, 580, 82)).save('roster_fixtures/oct02_pradeep_vishal.jpeg')
# 4. Ajay Jangid, Yamini: 580-735
im.crop((580, 62, 735, 82)).save('roster_fixtures/oct02_ajay_yamini.jpeg')
# 5. Govind, Rohit: 735-840
im.crop((735, 62, 840, 82)).save('roster_fixtures/oct02_govind_rohit.jpeg')
# 6. Ajay Jindoliya, Shiv, Munesh: 840-980
im.crop((840, 62, 980, 82)).save('roster_fixtures/oct02_off.jpeg')

print("Saved all oct02 items")
