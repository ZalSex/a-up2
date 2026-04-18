from PIL import Image
import os

ICON_SIZES = {
    'mdpi': 48, 'hdpi': 72, 'xhdpi': 96,
    'xxhdpi': 144, 'xxxhdpi': 192
}

img = Image.open('icon.jpg')

if img.mode == 'RGBA':
    bg = Image.new('RGB', img.size, (0, 0, 0))
    bg.paste(img, mask=img.split()[3])
    img = bg
elif img.mode != 'RGB':
    img = img.convert('RGB')

for density, size in ICON_SIZES.items():
    out_dir = f'android/app/src/main/res/mipmap-{density}'
    os.makedirs(out_dir, exist_ok=True)
    icon = img.resize((size, size), Image.LANCZOS)
    icon.save(os.path.join(out_dir, 'ic_launcher.png'), 'PNG')
    icon.save(os.path.join(out_dir, 'ic_launcher_round.png'), 'PNG')
    print(f"Generated {density}: {size}x{size}")

print("Icons generated!")
