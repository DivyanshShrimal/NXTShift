from PIL import Image

im = Image.open('roster_fixtures/crop_header.jpeg')
# Let's crop vertical slices:
# Month: 40-100
im.crop((40, 20, 100, 70)).save('roster_fixtures/hdr_col0_month.jpeg')
# 9am-5pm: 90-340
im.crop((90, 20, 340, 70)).save('roster_fixtures/hdr_col1_9to5.jpeg')
# 10am-6pm: 335-400
im.crop((335, 20, 400, 70)).save('roster_fixtures/hdr_col2_10to6.jpeg')
# 12pm-8pm: 395-460
im.crop((395, 20, 460, 70)).save('roster_fixtures/hdr_col3_12to8.jpeg')
# 8am-2pm: 455-585
im.crop((455, 20, 585, 70)).save('roster_fixtures/hdr_col4_8to2.jpeg')
# 2pm-8pm: 580-740
im.crop((580, 20, 740, 70)).save('roster_fixtures/hdr_col5_2to8.jpeg')
# 8pm-8am: 735-845
im.crop((735, 20, 845, 70)).save('roster_fixtures/hdr_col6_8to8.jpeg')
# Off: 840-985
im.crop((840, 20, 985, 70)).save('roster_fixtures/hdr_col7_off.jpeg')
# Leave: 980-1120
im.crop((980, 20, 1120, 70)).save('roster_fixtures/hdr_col8_leave.jpeg')

print("Saved all column headers")
