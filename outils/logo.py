"""Dessine le logo de TV Slim et en tire chaque fichier qui le porte.

Un téléviseur sans antenne, posé sur un pied central ; sur l'écran, six applications. Les deux en
trop, rouge et jaune, se désintègrent en pixels ; il reste le vert menthe et le bleu de TV Slim.
Grille de 108, comme les vecteurs Android. Sous 24 px, une version simplifiée prend le relais :
quatre tuiles et un cadre épais, que les pixels d'un .ico peuvent encore porter.

    pip install pillow fonttools uharfbuzz
    python outils/logo.py

La police du nom (Outfit, licence OFL) n'est utile qu'à la bannière du téléviseur : elle est
téléchargée depuis le dépôt Google Fonts et vérifiée par son empreinte, ou lue là où --police le dit.

Tous les fichiers ci-dessous sont produits ici : les retoucher à la main, c'est les perdre au
prochain passage.
"""

from __future__ import annotations

import argparse
import hashlib
import tempfile
import urllib.request
from dataclasses import dataclass, field, replace
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent

FOND = "#101418"
ECRAN = "#06090C"
CADRE = "#8AB4F8"
MENTHE = "#7FD1AE"
BLEU = "#5E97F6"
ROUGE = "#EA4335"
JAUNE = "#FBBC04"
NOM = "#EEF3F0"
SILHOUETTE = "#FFFFFF"

POLICE_URL = "https://github.com/google/fonts/raw/main/ofl/outfit/Outfit%5Bwght%5D.ttf"
POLICE_SHA256 = "fc7287273e66929776e2ba54f144fe699080bec29f61bf649d70d871468aeade"
POLICE_GRAISSE = 600

TAILLES_ICO = [256, 128, 64, 48, 40, 32, 24, 20, 16]
SURECHANTILLONNAGE = 8


@dataclass(frozen=True)
class Forme:
    """Un rectangle arrondi ou un polygone, dans les coordonnées de sa vue."""

    nature: str
    x: float = 0
    y: float = 0
    w: float = 0
    h: float = 0
    r: float = 0
    points: tuple = ()
    remplissage: str | None = None
    opacite: float = 1.0
    trait: str | None = None
    epaisseur: float = 0.0
    chemin: str = ""


def rect(x, y, w, h, r, remplissage=None, opacite=1.0, trait=None, epaisseur=0.0) -> Forme:
    return Forme("rect", x, y, w, h, r, remplissage=remplissage, opacite=opacite, trait=trait, epaisseur=epaisseur)


def poly(points, remplissage) -> Forme:
    return Forme("poly", points=tuple(points), remplissage=remplissage)


@dataclass(frozen=True)
class Geometrie:
    cadre: tuple            # x, y, w, h, rayon, épaisseur du trait
    tuiles_x: tuple
    tuiles_y: tuple
    tuile: tuple            # w, h, rayon
    carte: tuple            # couleur de chaque tuile, ligne par ligne
    cou: tuple
    socle: tuple            # x, y, w, h, rayon
    pixels: bool            # la poussière ne tient qu'en grand
    part_restante: float


GRANDE = Geometrie(
    cadre=(17, 22, 74, 50, 7, 6),
    tuiles_x=(26, 46 + 1 / 3, 66 + 2 / 3),
    tuiles_y=(31, 49.5),
    tuile=(15 + 1 / 3, 13.5, 2.5),
    carte=(("menthe", "rouge", "bleu"), ("bleu", "menthe", "jaune")),
    cou=((49, 75), (59, 75), (61, 81), (47, 81)),
    socle=(34, 81, 40, 6, 3),
    pixels=True,
    part_restante=0.42,
)

PETITE = Geometrie(
    cadre=(14, 16, 80, 60, 9, 10),
    tuiles_x=(25, 57),
    tuiles_y=(27, 49),
    tuile=(26, 16, 3),
    carte=(("menthe", "rouge"), ("jaune", "bleu")),
    cou=((48, 81), (60, 81), (61, 86), (47, 86)),
    socle=(30, 86, 48, 7, 3.5),
    pixels=False,
    part_restante=0.45,
)

# Ce qui reste d'une tuile qui se désintègre : une colonne de pixels par tranche, de moins en moins
# pleine et de plus en plus transparente, puis trois miettes qui s'envolent vers la droite.
PIXELS_GARDES = ((1, 1, 0, 1), (0, 1, 1, 0), (1, 0, 0, 0))
PIXELS_OPACITE = (0.9, 0.6, 0.35)
MIETTES = ((3.05, 0.3, 0.45, 0.3), (3.4, 1.6, 0.38, 0.2), (3.2, 2.8, 0.32, 0.15))


def palette(mono: bool) -> dict:
    if mono:
        return dict(ecran=None, cadre=SILHOUETTE, menthe=SILHOUETTE, bleu=SILHOUETTE, rouge=SILHOUETTE, jaune=SILHOUETTE)
    return dict(ecran=ECRAN, cadre=CADRE, menthe=MENTHE, bleu=BLEU, rouge=ROUGE, jaune=JAUNE)


def marque(petite: bool = False, mono: bool = False) -> list[Forme]:
    """Le téléviseur seul, sans fond, dans la grille de 108."""
    g = PETITE if petite else GRANDE
    p = palette(mono)
    x, y, w, h, r, e = g.cadre
    formes = [
        poly(g.cou, p["cadre"]),
        rect(*g.socle, remplissage=p["cadre"]),
        rect(x, y, w, h, r, remplissage=p["ecran"], trait=p["cadre"], epaisseur=e),
    ]
    tw, th, tr = g.tuile
    for ligne, couleurs in enumerate(g.carte):
        for colonne, nom in enumerate(couleurs):
            tx, ty, couleur = g.tuiles_x[colonne], g.tuiles_y[ligne], p[nom]
            if nom not in ("rouge", "jaune"):
                formes.append(rect(tx, ty, tw, th, tr, couleur))
                continue
            x0 = tx + tw * g.part_restante
            formes.append(rect(tx, ty, tw * g.part_restante, th, tr, couleur))
            formes.append(rect(x0 - tr, ty, tr, th, 0, couleur))
            if not g.pixels:
                continue
            s = th / 4
            for c, garde in enumerate(PIXELS_GARDES):
                for rang, oui in enumerate(garde):
                    if oui:
                        formes.append(rect(x0 + c * s + 0.35, ty + rang * s + 0.35, s - 0.7, s - 0.7, 0.4, couleur, PIXELS_OPACITE[c]))
            for dx, dy, k, o in MIETTES:
                formes.append(rect(x0 + dx * s, ty + dy * s, k * s, k * s, 0.3, couleur, o))
    return formes


def transformer(formes: list[Forme], echelle: float, dx: float, dy: float) -> list[Forme]:
    def pt(px, py):
        return (px * echelle + dx, py * echelle + dy)

    sortie = []
    for f in formes:
        if f.nature == "poly":
            sortie.append(replace(f, points=tuple(pt(*p) for p in f.points)))
        elif f.nature == "rect":
            x, y = pt(f.x, f.y)
            sortie.append(replace(f, x=x, y=y, w=f.w * echelle, h=f.h * echelle, r=f.r * echelle, epaisseur=f.epaisseur * echelle))
        else:
            sortie.append(f)
    return sortie


def icone_pleine(petite: bool = False) -> list[Forme]:
    """Le carré arrondi de Windows, de GitHub et des aperçus."""
    return [rect(0, 0, 108, 108, 24, FOND)] + marque(petite)


def premier_plan_adaptatif(mono: bool = False) -> list[Forme]:
    """Une icône adaptative ne montre que les 72 du centre, sous un masque rond ou arrondi : le
    téléviseur tient dans le cercle de 66 que garantit Android."""
    echelle = 0.62
    return transformer(marque(mono=mono), echelle, 54 - 54 * echelle, 54 - 53 * echelle)


# --------------------------------------------------------------------------------------------
# Le nom, pour la bannière : des tracés, puisqu'un vecteur Android ne sait pas écrire.

@dataclass
class Mot:
    chemins: list = field(default_factory=list)   # (pathData, couleur)


def police_outfit(chemin: str | None) -> Path:
    if chemin:
        return Path(chemin)
    cache = Path(tempfile.gettempdir()) / "tvslim-logo" / "Outfit-wght.ttf"
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(POLICE_URL, timeout=30) as reponse:
            cache.write_bytes(reponse.read())
    empreinte = hashlib.sha256(cache.read_bytes()).hexdigest()
    if empreinte != POLICE_SHA256:
        cache.unlink()
        raise SystemExit(f"Police inattendue ({empreinte}) : rien n'est écrit.")
    return cache


def ecrire_nom(police: Path, morceaux: list[tuple[str, str]], taille: float, x: float, ligne_de_base: float) -> Mot:
    """Met en forme le texte entier (crénage « TV » compris), puis rend chaque glyphe dans la couleur
    du morceau d'où il vient."""
    import uharfbuzz as hb
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont

    texte = "".join(t for t, _ in morceaux)
    donnees = police.read_bytes()
    face = hb.Face(donnees)
    fonte = hb.Font(face)
    fonte.set_variations({"wght": POLICE_GRAISSE})
    tampon = hb.Buffer()
    tampon.add_str(texte)
    tampon.guess_segment_properties()
    hb.shape(fonte, tampon)

    statique = instantiateVariableFont(TTFont(police), {"wght": POLICE_GRAISSE})
    glyphes = statique.getGlyphSet()
    ordre = statique.getGlyphOrder()
    echelle = taille / statique["head"].unitsPerEm

    bornes, debut = [], 0
    for morceau, couleur in morceaux:
        bornes.append((debut, debut + len(morceau), couleur))
        debut += len(morceau)

    mot, avance = Mot(), 0.0
    for info, pos in zip(tampon.glyph_infos, tampon.glyph_positions):
        couleur = next(c for a, b, c in bornes if a <= info.cluster < b)
        stylo = SVGPathPen(glyphes, ntos=lambda n: f"{n:.2f}".rstrip("0").rstrip("."))
        gx = x + (avance + pos.x_offset) * echelle
        glyphes[ordre[info.codepoint]].draw(TransformPen(stylo, (echelle, 0, 0, -echelle, gx, ligne_de_base)))
        d = stylo.getCommands()
        if d:
            mot.chemins.append((d, couleur))
        avance += pos.x_advance
    return mot


# --------------------------------------------------------------------------------------------
# Écritures

def nombre(n: float) -> str:
    texte = f"{n:.3f}".rstrip("0").rstrip(".")
    return "0" if texte in ("-0", "") else texte


def chemin_rect(f: Forme) -> str:
    r = min(f.r, f.w / 2, f.h / 2)
    if r <= 0:
        return f"M{nombre(f.x)},{nombre(f.y)}h{nombre(f.w)}v{nombre(f.h)}h{nombre(-f.w)}z"
    a = f"{nombre(r)},{nombre(r)} 0 0 1"
    return (f"M{nombre(f.x + r)},{nombre(f.y)}h{nombre(f.w - 2 * r)}a{a} {nombre(r)},{nombre(r)}"
            f"v{nombre(f.h - 2 * r)}a{a} {nombre(-r)},{nombre(r)}h{nombre(-(f.w - 2 * r))}"
            f"a{a} {nombre(-r)},{nombre(-r)}v{nombre(-(f.h - 2 * r))}a{a} {nombre(r)},{nombre(-r)}z")


def chemin(f: Forme) -> str:
    if f.nature == "rect":
        return chemin_rect(f)
    if f.nature == "poly":
        (x0, y0), *reste = f.points
        return f"M{nombre(x0)},{nombre(y0)}" + "".join(f"L{nombre(x)},{nombre(y)}" for x, y in reste) + "z"
    return f.chemin


def vecteur_android(formes: list[Forme], largeur: int, hauteur: int, vue_l: int, vue_h: int, note: str) -> str:
    lignes = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {note} Produit par outils/logo.py : ne pas retoucher à la main. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{largeur}dp"',
        f'    android:height="{hauteur}dp"',
        f'    android:viewportWidth="{vue_l}"',
        f'    android:viewportHeight="{vue_h}">',
    ]
    for f in formes:
        attributs = [f'android:pathData="{chemin(f)}"']
        attributs.append(f'android:fillColor="{f.remplissage}"' if f.remplissage else 'android:fillColor="#00000000"')
        if f.opacite < 1:
            attributs.append(f'android:fillAlpha="{nombre(f.opacite)}"')
        if f.trait:
            attributs.append(f'android:strokeColor="{f.trait}"')
            attributs.append(f'android:strokeWidth="{nombre(f.epaisseur)}"')
        lignes.append("    <path\n        " + "\n        ".join(attributs) + " />")
    lignes.append("</vector>")
    return "\n".join(lignes) + "\n"


def svg(formes: list[Forme], vue_l: int, vue_h: int, titre: str) -> str:
    corps = []
    for f in formes:
        attributs = [f'd="{chemin(f)}"', f'fill="{f.remplissage or "none"}"']
        if f.opacite < 1:
            attributs.append(f'fill-opacity="{nombre(f.opacite)}"')
        if f.trait:
            attributs.append(f'stroke="{f.trait}" stroke-width="{nombre(f.epaisseur)}"')
        corps.append(f"  <path {' '.join(attributs)}/>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vue_l} {vue_h}" role="img">\n'
            f"  <title>{titre}</title>\n" + "\n".join(corps) + "\n</svg>\n")


def adaptative(note: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {note} Produit par outils/logo.py : ne pas retoucher à la main. -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_logo_fond" />\n'
        '    <foreground android:drawable="@drawable/ic_logo_premier_plan" />\n'
        '    <monochrome android:drawable="@drawable/ic_logo_monochrome" />\n'
        "</adaptive-icon>\n"
    )


def dessiner(formes: list[Forme], cote: int, vue: int = 108):
    """Rend les formes au pixel près pour le .ico : suréchantillonné, puis réduit."""
    from PIL import Image, ImageDraw

    grand = cote * SURECHANTILLONNAGE
    k = grand / vue
    image = Image.new("RGBA", (grand, grand), (0, 0, 0, 0))

    def rgba(couleur: str, opacite: float):
        c = couleur.lstrip("#")
        return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), round(255 * opacite))

    for f in formes:
        calque = Image.new("RGBA", image.size, (0, 0, 0, 0)) if f.opacite < 1 else None
        trace = ImageDraw.Draw(calque or image)
        if f.nature == "poly":
            trace.polygon([(x * k, y * k) for x, y in f.points], fill=rgba(f.remplissage, f.opacite))
        else:
            # Un trait SVG est centré sur le tracé ; celui de Pillow est intérieur à la boîte.
            demi = f.epaisseur / 2 if f.trait else 0
            boite = [(f.x - demi) * k, (f.y - demi) * k, (f.x + f.w + demi) * k, (f.y + f.h + demi) * k]
            rayon = min(f.r + demi, (f.w + 2 * demi) / 2, (f.h + 2 * demi) / 2) * k
            trace.rounded_rectangle(
                boite,
                radius=rayon,
                fill=rgba(f.remplissage, f.opacite) if f.remplissage else None,
                outline=rgba(f.trait, 1) if f.trait else None,
                width=round(f.epaisseur * k) if f.trait else 0,
            )
        if calque is not None:
            image = Image.alpha_composite(image, calque)
    return image.resize((cote, cote), Image.LANCZOS)


# --------------------------------------------------------------------------------------------

def ecrire(chemin_relatif: str, contenu) -> None:
    cible = RACINE / chemin_relatif
    cible.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(contenu, str):
        cible.write_text(contenu, encoding="utf-8", newline="\n")
    else:
        cible.write_bytes(contenu)
    print(f"  {chemin_relatif}")


def main() -> None:
    arguments = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    arguments.add_argument("--police", help="Outfit[wght].ttf, si elle est déjà sur le disque")
    options = arguments.parse_args()

    print("Logo de TV Slim :")
    ecrire("docs/logo/tvslim.svg", svg(icone_pleine(), 108, 108, "TV Slim"))
    ecrire("docs/logo/tvslim-petit.svg", svg(icone_pleine(petite=True), 108, 108, "TV Slim, 24 px et moins"))

    fond = vecteur_android([rect(0, 0, 108, 108, 0, FOND)], 108, 108, 108, 108, "Fond de l'icône adaptative.")
    plan = vecteur_android(premier_plan_adaptatif(), 108, 108, 108, 108,
                           "Premier plan de l'icône adaptative : le téléviseur, dans le cercle de 66.")
    mono = vecteur_android(premier_plan_adaptatif(mono=True), 108, 108, 108, 108,
                           "Couche monochrome, pour les icônes à thème d'Android 13 : seule son opacité compte.")
    for module, icone in (("app-mobile", "ic_tvslim_remote"), ("app-tv", "ic_tvslim")):
        res = f"TVSlim/{module}/src/main/res/drawable"
        ecrire(f"{res}/{icone}.xml", adaptative("Icône de l'application, la même sur le téléphone, le téléviseur et Windows."))
        ecrire(f"{res}/ic_logo_fond.xml", fond)
        ecrire(f"{res}/ic_logo_premier_plan.xml", plan)
        ecrire(f"{res}/ic_logo_monochrome.xml", mono)

    # Dans l'application elle-même, un vecteur ordinaire : Compose n'affiche pas une icône adaptative.
    ecrire("TVSlim/app-mobile/src/main/res/drawable/ic_logo.xml",
           vecteur_android(icone_pleine(), 108, 108, 108, 108, "Le logo dans la barre du haut du téléphone."))

    # La notification du gardien : Android n'en garde que la silhouette, d'où la version blanche,
    # dessinée en petit (quatre tuiles), à 24 dp.
    silhouette = transformer(marque(petite=True, mono=True), 24 / 108, 0, 0)
    ecrire("TVSlim/app-tv/src/main/res/drawable/ic_notification.xml",
           vecteur_android(silhouette, 24, 24, 24, 24, "Petite icône des notifications : une silhouette blanche."))

    nom = ecrire_nom(police_outfit(options.police), [("TV", CADRE), (" Slim", NOM)], 42, 152, 107)
    banniere = [rect(0, 0, 320, 180, 0, FOND)] + transformer(marque(), 1.22, 88 - 54 * 1.22, 92 - 53 * 1.22)
    banniere += [Forme("chemin", remplissage=couleur, chemin=d) for d, couleur in nom.chemins]
    ecrire("TVSlim/app-tv/src/main/res/drawable/tv_banner.xml",
           vecteur_android(banniere, 320, 180, 320, 180, "Bannière Android TV, 320 × 180 : le logo et son nom."))

    ecrire("TVSlim Windows/src/commonMain/composeResources/drawable/ic_tvslim.xml",
           vecteur_android(icone_pleine(), 108, 108, 108, 108, "Icône de TV Slim pour Windows : fenêtre et « À propos »."))

    images = [dessiner(icone_pleine(petite=cote <= 24), cote) for cote in TAILLES_ICO]
    ico = RACINE / "TVSlim Windows/packaging/tvslim.ico"
    images[0].save(ico, format="ICO", sizes=[(c, c) for c in TAILLES_ICO], append_images=images[1:])
    print(f"  TVSlim Windows/packaging/tvslim.ico — {', '.join(str(c) for c in TAILLES_ICO)} px")
    images[0].save(RACINE / "TVSlim Windows/packaging/tvslim-256.png")
    print("  TVSlim Windows/packaging/tvslim-256.png")
    dessiner(icone_pleine(), 512).save(RACINE / "docs/logo/tvslim-512.png")
    print("  docs/logo/tvslim-512.png")


if __name__ == "__main__":
    main()
