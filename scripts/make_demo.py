"""Generate clearly labelled, non-private PDFs/images for device smoke tests and screenshots."""
from pathlib import Path
import io
import fitz
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "output" / "demo"
OUT.mkdir(parents=True, exist_ok=True)
PURPLE = (0.369, 0.416, 0.824)
INK = (0.13, 0.15, 0.21)

def page(doc, title, eyebrow, number):
    p = doc.new_page(width=595, height=842)
    p.draw_rect(fitz.Rect(0, 0, 595, 14), color=None, fill=PURPLE)
    p.insert_text((48, 64), "PAPERJOIN   /   SAMPLE DOCUMENT", fontsize=10, color=PURPLE)
    p.insert_text((48, 116), eyebrow, fontsize=12, color=INK)
    p.insert_text((48, 164), title, fontsize=32, fontname="hebo", color=INK)
    p.insert_text((48, 205), "A little order. A lot more clarity.", fontsize=14, color=(.4,.43,.49))
    p.draw_line((48, 238), (547, 238), color=(.82,.84,.9))
    p.insert_text((48, 800), "DEMO ONLY  -  No personal or customer information", fontsize=9, color=(.45,.47,.53))
    p.insert_text((510, 800), f"{number:02}", fontsize=11, color=PURPLE)
    return p

doc = fitz.open()
p = page(doc, "Project overview", "01 / THE BIG PICTURE", 1)
for i, (title, detail) in enumerate([
    ("Collect", "Bring your PDFs and images into one workspace."),
    ("Arrange", "Preview every page. Drag to find the right order."),
    ("Make it yours", "Rotate, compress and save a single clear document."),
]):
    y = 292 + i * 126
    p.draw_rect(fitz.Rect(48, y - 18, 547, y + 78), color=None, fill=(.96,.96,.98))
    p.insert_text((68, y + 8), title, fontsize=18, fontname="hebo", color=INK)
    p.insert_text((68, y + 39), detail, fontsize=11, color=INK)
p = page(doc, "A simple checklist", "02 / READY WHEN YOU ARE", 2)
for i, line in enumerate(["Add the source documents", "Check the reading direction", "Put pages in the right order", "Choose a compression setting", "Save and share your finished PDF"]):
    y = 290 + i * 66
    p.draw_rect(fitz.Rect(50,y-12,66,y+4), color=PURPLE)
    p.insert_text((86, y), line, fontsize=14, color=INK)
doc.save(OUT / "01_Project.pdf")
doc.close()

doc = fitz.open()
p = page(doc, "Meeting notes", "03 / A FRESH PERSPECTIVE", 3)
for i, line in enumerate(["These pages are intentionally rotated for testing.", "Automatic rotation should make this text upright.", "The source document will always remain unchanged.", "This sample contains no private information."]):
    p.insert_text((48, 290+i*40), line, fontsize=14, color=INK)
p.set_rotation(90)
doc.save(OUT / "02_Rotated.pdf")
doc.close()

image = Image.new("RGB", (1200, 1600), "#f7f6f2")
d = ImageDraw.Draw(image)
font_path = Path("C:/Windows/Fonts/arial.ttf")
font = ImageFont.truetype(str(font_path), 35)
bold = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 64)
d.rectangle((0,0,1200,30), fill="#5e6ad2")
d.text((90,100), "PAPERJOIN / SCANNED SAMPLE", font=font, fill="#5e6ad2")
d.text((90,220), "Ideas on paper.", font=bold, fill="#252a38")
for i, text in enumerate(["Everything in one place.", "This is an image, not a text-based PDF.", "Preview pages before you combine them.", "Rotate the scan to its correct direction.", "Keep your documents clear and readable.", "All processing happens on this device."]):
    d.text((90,400+i*95), text, font=font, fill="#303544")
d.rounded_rectangle((90,1080,1110,1350), radius=16, fill="#e4e6f4")
d.text((140,1150), "DEMO ONLY", font=bold, fill="#5e6ad2")
d.text((90,1490), "No personal or customer information.", font=font, fill="#666a75")
image.rotate(180).save(OUT / "03_Scan.jpg", quality=95)
print(OUT)
