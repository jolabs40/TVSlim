"""Dessine l'icône Windows de TV Slim : packaging/tvslim.ico.

Même tracé que src/commonMain/composeResources/drawable/ic_tvslim.xml, sur une grille de 108 :
un écran de téléviseur coché, et l'ordinateur qui le pilote. Chaque taille est dessinée à part,
suréchantillonnée, pour rester nette jusqu'à 16 pixels.

    pip install pillow
    python outils/icone.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

FOND = (16, 20, 24, 255)
MENTHE = (127, 209, 174, 255)
BLEU = (138, 180, 248, 255)
TAILLES = [256, 128, 64, 48, 40, 32, 24, 20, 16]
SURECHANTILLONNAGE = 8


def dessiner(cote: int) -> Image.Image:
    grand = cote * SURECHANTILLONNAGE
    echelle = grand / 108
    image = Image.new("RGBA", (grand, grand), (0, 0, 0, 0))
    trace = ImageDraw.Draw(image)

    # Aux petites tailles, un trait de 5/108 tomberait sous le pixel : on l'épaissit.
    trait = max(5 * echelle, 1.4 * SURECHANTILLONNAGE)

    def boite(x0, y0, x1, y1, marge=0.0):
        return [(x0 - marge) * echelle, (y0 - marge) * echelle, (x1 + marge) * echelle, (y1 + marge) * echelle]

    def contour(x0, y0, x1, y1, rayon, couleur, fond=None):
        # Un trait Android est centré sur le tracé ; celui de Pillow est intérieur à la boîte.
        demi = trait / 2 / echelle
        trace.rounded_rectangle(
            boite(x0, y0, x1, y1, demi),
            radius=(rayon + demi) * echelle,
            outline=couleur,
            width=round(trait),
            fill=fond,
        )

    trace.rounded_rectangle(boite(0, 0, 108, 108), radius=22 * echelle, fill=FOND)
    contour(14, 22, 80, 62, 4, MENTHE)

    coche = [(34 * echelle, 42 * echelle), (42 * echelle, 50 * echelle), (58 * echelle, 33 * echelle)]
    trace.line(coche, fill=MENTHE, width=round(trait), joint="curve")
    rayon = trait / 2
    for x, y in (coche[0], coche[-1]):
        trace.ellipse([x - rayon, y - rayon, x + rayon, y + rayon], fill=MENTHE)

    contour(59, 66, 93, 89, 3, BLEU, fond=FOND)
    trace.rectangle(boite(70, 93, 82, 98), fill=BLEU)

    return image.resize((cote, cote), Image.LANCZOS)


def main() -> None:
    racine = Path(__file__).resolve().parent.parent
    sortie = racine / "packaging" / "tvslim.ico"
    sortie.parent.mkdir(parents=True, exist_ok=True)
    images = [dessiner(cote) for cote in TAILLES]
    images[0].save(sortie, format="ICO", sizes=[(c, c) for c in TAILLES], append_images=images[1:])
    images[0].save(racine / "packaging" / "tvslim-256.png")
    print(f"{sortie} — {', '.join(str(c) for c in TAILLES)} px")


if __name__ == "__main__":
    main()
