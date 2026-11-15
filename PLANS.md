# Format des plans « citizen-plan-1 »

Un plan est un fichier JSON déposé dans `plans/<id>.json` (ou importé sur la carte).

```json
{
  "format": "citizen-plan-1",
  "demande": "C-0001",
  "nom": "Taverne du Sanglier",
  "origine": [120, 64, -40],
  "rotation": 0,
  "palette": {
    "P": "minecraft:spruce_planks",
    "L": "minecraft:stripped_spruce_log[axis=y]",
    "V": "minecraft:glass_pane",
    "T": "minecraft:wall_torch[facing=south]"
  },
  "operations": [
    {"op": "vider",    "de": [-1, 1, -1], "a": [12, 12, 10]},
    {"op": "remplir",  "de": [0, 0, 0],   "a": [11, 0, 9],  "bloc": "minecraft:cobblestone"},
    {"op": "murs",     "de": [0, 1, 0],   "a": [11, 4, 9],  "bloc": "P"},
    {"op": "bloc",     "pos": [5, 1, 0],  "bloc": "minecraft:spruce_door[facing=north,half=lower]"},
    {"op": "couches",  "de": [0, 5, 0],   "couches": [["LPPPPPPPPPPL", "P..........P"]]}
  ]
}
```

- Coordonnées des opérations **relatives à `origine`** (X vers l'est, Y vers le haut, Z vers le sud).
- `rotation` : 0, 90, 180 ou 270 (sens horaire autour de l'origine) ; les blocs orientés tournent aussi.
- Un bloc = clé de la palette **ou** bloc Minecraft complet (`minecraft:oak_stairs[facing=east,half=top]`,
  NBT accepté : `minecraft:chest[facing=west]{Items:[...]}`).
- Opérations : `remplir`, `boite` (creux), `murs`, `vider` (air), `bloc`, `blocs`, `ligne`, `cylindre`
  (`centre`, `rayon`, `hauteur`, `creux`), `sphere` (`centre`, `rayon`, `creux`, `demi`), `couches`.
- `couches` : du bas vers le haut ; chaque couche = lignes vers +Z ; chaque caractère vers +X ;
  `.` ou espace = ne rien poser, `_` = air, autre = clé de palette (1 caractère).
- Les opérations suivantes écrasent les précédentes au même endroit.
- Moitiés hautes des portes / grandes plantes et têtes de lit ajoutées automatiquement.
- Barrières, vitres, murets, escaliers, coffres doubles se raccordent automatiquement à la fin.
- Le plan ne doit pas déborder de plus de 16 blocs autour de la zone sélectionnée. Max 1 500 000 blocs.
