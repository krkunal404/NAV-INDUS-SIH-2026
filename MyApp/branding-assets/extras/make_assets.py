import numpy as np, os, shutil
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

UP = "/mnt/user-data/uploads/"
OUT = "/home/claude/brand/out"
shutil.rmtree(OUT, ignore_errors=True)
R = OUT + "/res"
for d in ["drawable-nodpi","mipmap-mdpi","mipmap-hdpi","mipmap-xhdpi","mipmap-xxhdpi","mipmap-xxxhdpi","mipmap-anydpi-v26","values","values-v31"]:
    os.makedirs(f"{R}/{d}", exist_ok=True)

def cutout(path, frame=6, bg=None, tol=38):
    """Make ONLY the outside background transparent (interior whites stay opaque),
    with soft anti-aliased edges. Returns RGBA image cropped to content."""
    im = Image.open(path).convert("RGB")
    a = np.array(im).astype(np.float32)
    a = a[frame:-frame, frame:-frame]           # drop scanner/edge artifacts
    h, w, _ = a.shape
    if bg is None:
        bg = np.median(np.concatenate([a[:15].reshape(-1,3), a[-15:].reshape(-1,3)]), axis=0)
    dist = np.abs(a - bg).max(axis=2)
    near = dist < tol
    lab, n = ndi.label(near)
    border_labels = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:,0], lab[:,-1]]))) - {0}
    outside = np.isin(lab, list(border_labels))
    # soft alpha only on a thin ring next to the outside region
    ring = ndi.binary_dilation(outside, iterations=3) & ~outside
    ca = np.clip(np.max((bg - a) / np.maximum(bg, 1), axis=2), 0, 1)     # color-to-alpha
    ca = np.clip((ca - 0.04) / 0.96, 0, 1)
    alpha = np.ones((h, w), np.float32)
    alpha[outside] = 0
    alpha[ring] = np.maximum(ca[ring], 0.0)
    # unpremultiply colours on the ring so edges don't get a white halo
    rgb = a.copy()
    with np.errstate(divide="ignore", invalid="ignore"):
        un = bg + (a - bg) / np.maximum(alpha[..., None], 1e-3)
    rgb[ring] = np.clip(un[ring], 0, 255)
    out = np.dstack([rgb, alpha * 255]).astype(np.uint8)
    img = Image.fromarray(out, "RGBA")
    bbox = img.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return img.crop(bbox)

def fit(img, box_w, box_h):
    r = min(box_w / img.width, box_h / img.height)
    return img.resize((max(1, round(img.width * r)), max(1, round(img.height * r))), Image.LANCZOS)

def on_canvas(img, size, scale, bgcolor=None, dy=0):
    canvas = Image.new("RGBA", (size, size), bgcolor or (0,0,0,0))
    f = fit(img, size * scale, size * scale)
    canvas.alpha_composite(f, ((size - f.width)//2, (size - f.height)//2 + dy))
    return canvas

mark = cutout(UP + "8-2.png")                 # image 1: logo only
splash = cutout(UP + "8.png")                 # image 2: logo + wordmark + tagline
print("mark", mark.size, "splash", splash.size)

# ---- adaptive icon foreground (108dp -> 432px), logo inside ~ safe zone
on_canvas(mark, 432, 0.53).save(f"{R}/drawable-nodpi/nimo_icon_foreground.png", optimize=True)

# ---- legacy icons (API 24-25) on white
sizes = {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}
for dpi, s in sizes.items():
    sq = on_canvas(mark, s*4, 0.88, (255,255,255,255)).resize((s, s), Image.LANCZOS)
    sq.save(f"{R}/mipmap-{dpi}/ic_launcher.webp", "WEBP", quality=95)
    big = on_canvas(mark, s*4, 0.78, (255,255,255,255))
    mask = Image.new("L", big.size, 0); ImageDraw.Draw(mask).ellipse([0,0,big.width-1,big.height-1], fill=255)
    rnd = Image.new("RGBA", big.size, (0,0,0,0)); rnd.paste(big, (0,0), mask)
    rnd.resize((s, s), Image.LANCZOS).save(f"{R}/mipmap-{dpi}/ic_launcher_round.webp", "WEBP", quality=95)

# ---- in-app logo (top bar) and splash logo
fit(mark, 512, 512).save(f"{R}/drawable-nodpi/nimo_logo.png", optimize=True)
fit(splash, 1000, 1400).save(f"{R}/drawable-nodpi/nimo_splash_logo.png", optimize=True)
Image.new("RGBA", (288, 288), (0,0,0,0)).save(f"{R}/drawable-nodpi/nimo_splash_empty.png")

# ---- store / submission icon (not used by the app build)
os.makedirs(OUT + "/extras", exist_ok=True)
on_canvas(mark, 512, 0.78, (255,255,255,255)).convert("RGB").save(OUT + "/extras/nimo_playstore_512.png")

# ---- previews for my own QA
def maskcircle(im):
    m = Image.new("L", im.size, 0); ImageDraw.Draw(m).ellipse([0,0,im.width-1,im.height-1], fill=255)
    o = Image.new("RGBA", im.size, (220,220,220,255)); o.paste(im, (0,0), m); return o
fg = Image.open(f"{R}/drawable-nodpi/nimo_icon_foreground.png")
comp = Image.new("RGBA", (432,432), (255,255,255,255)); comp.alpha_composite(fg)
# adaptive mask shows the central 72/108 of the canvas
c = comp.crop((72,72,360,360)).resize((288,288))
prev = Image.new("RGBA", (288*3+40, 300), (200,200,200,255))
prev.paste(maskcircle(c), (10,6))
sq = comp.crop((72,72,360,360)); prev.paste(sq, (298+10,6))
prev.paste(Image.open(f"{R}/mipmap-xxxhdpi/ic_launcher.webp").convert("RGBA").resize((192,192)), (596+30,6))
prev.save("/home/claude/brand/preview_icon.png")
sp = Image.open(f"{R}/drawable-nodpi/nimo_splash_logo.png")
bgp = Image.new("RGBA", (sp.width+80, sp.height+80), (255,255,255,255)); bgp.alpha_composite(sp,(40,40)); bgp.convert("RGB").save("/home/claude/brand/preview_splash_white.png")
dk = Image.new("RGBA", (sp.width+80, sp.height+80), (30,30,40,255)); dk.alpha_composite(sp,(40,40)); dk.convert("RGB").resize((400,int(400*dk.height/dk.width))).save("/home/claude/brand/preview_splash_dark.png")
print("done")
