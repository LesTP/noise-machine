"""Generate Play Store listing assets for Noise Machine."""
from PIL import Image, ImageDraw, ImageFont
import os

BASE = r"c:\Users\myeluashvili\claude-code-workspace\projects\noise-machine"
OUT = os.path.join(BASE, "store-assets")
os.makedirs(OUT, exist_ok=True)

DARK_NAVY = (15, 26, 46)      # #0F1A2E
MUTED_BLUE = (90, 123, 175)   # #5A7BAF
WHITE = (255, 255, 255)

# --- 1. Hi-res icon (512x512) ---
# Composite foreground onto navy background
fg_path = os.path.join(BASE, "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher_foreground.png")
fg = Image.open(fg_path).convert("RGBA")

# Create 512x512 navy background
icon = Image.new("RGBA", (512, 512), DARK_NAVY)

# Scale foreground to 512x512 (it's an adaptive icon foreground, 432px at xxxhdpi)
fg_resized = fg.resize((512, 512), Image.LANCZOS)
icon = Image.alpha_composite(icon, fg_resized)

# Convert to RGB (Play Store wants no alpha)
icon_rgb = icon.convert("RGB")
icon_rgb.save(os.path.join(OUT, "icon-512.png"), "PNG")
print("Created icon-512.png")

# --- 2. Feature graphic (1024x500) ---
feature = Image.new("RGB", (1024, 500), DARK_NAVY)
draw = ImageDraw.Draw(feature)

# Place shell icon centered vertically, left-of-center
shell_size = 280
fg_feat = fg.resize((shell_size, shell_size), Image.LANCZOS)
shell_x = 1024 // 2 - shell_size // 2
shell_y = (500 - shell_size) // 2
feature.paste(fg_feat, (shell_x, shell_y), fg_feat)

# Add app name below shell
try:
    font_large = ImageFont.truetype("arial.ttf", 42)
    font_small = ImageFont.truetype("arial.ttf", 22)
except:
    font_large = ImageFont.load_default()
    font_small = ImageFont.load_default()

# "Noise Machine" text centered below icon
text = "Noise Machine"
bbox = draw.textbbox((0, 0), text, font=font_large)
tw = bbox[2] - bbox[0]
text_x = (1024 - tw) // 2
text_y = shell_y + shell_size + 15
draw.text((text_x, text_y), text, fill=MUTED_BLUE, font=font_large)

# Tagline
tagline = "Sleep noise, shaped by you"
bbox2 = draw.textbbox((0, 0), tagline, font=font_small)
tw2 = bbox2[2] - bbox2[0]
draw.text(((1024 - tw2) // 2, text_y + 50), tagline, fill=(130, 155, 195), font=font_small)

feature.save(os.path.join(OUT, "feature-graphic-1024x500.png"), "PNG")
print("Created feature-graphic-1024x500.png")

print("\nAll store assets created in:", OUT)
