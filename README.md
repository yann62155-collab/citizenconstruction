# Citizen Construction — NeoForge 21.1.215 / Minecraft 1.21.1

Sélectionne une zone sur la **carte 3D** (mod [Citizen Map](https://github.com/yann62155-collab/citizenmap)),
décris ce que tu veux, **Claude dessine le plan**, et le mod le **construit en direct dans le monde, sans redémarrer le serveur**.
Mod **serveur uniquement**. Nécessite `citizenmap`.

## Installation
1. Mets `citizenmap-x.x.x.jar` **et** `citizenconstruction-x.x.x.jar` (Releases) dans `mods/` du serveur, redémarre une fois.
2. Ouvre la carte, panneau **Administration** → mot de passe (`/carte motdepasse` en jeu).

## Utilisation
1. Panneau **Constructions** → **Sélectionner une zone** → clique-glisse sur la carte (clic droit = tourner la vue).
2. Règle la hauteur, donne un titre, décris ce que tu veux → **Envoyer la demande à Claude**.
3. Claude répond avec un plan :
   - **pont automatique** (si `github.token` est rempli) : la demande part dans `demandes/C-0001.json`, Claude dépose
     `plans/C-0001.json`, le mod le récupère tout seul (toutes les 30 s) ;
   - **sinon** : bouton **Copier pour Claude** → colle dans ta discussion → **Importer un plan** avec le fichier reçu.
4. **Aperçu** : le bâtiment s'affiche en fantôme sur la carte. **Construire** : les blocs sont posés en direct
   (800 blocs par tick par défaut), la carte se met à jour pendant la construction.
5. **Annuler** : remet le terrain **exactement** comme avant (blocs, coffres et leur contenu).

## Commandes (op)
`/construction liste` · `info <id>` · `construire <id>` · `annuler <id>` · `stop` · `github` · `recharger`

## Configuration (`config/citizenconstruction.properties`, rechargeable avec `/construction recharger`)
| Clé | Défaut | Rôle |
|---|---|---|
| `blocs.par.tick` | 800 | vitesse de construction (baisser si lag) |
| `selection.taille.max` | 256 | taille max d'une zone (X et Z) |
| `selection.hauteur.max` | 160 | hauteur max au-dessus du sol |
| `construction.auto` | false | construire dès que le plan arrive |
| `github.token` | *(vide)* | jeton GitHub « fine-grained », accès **Contents : Read and write** à ce dépôt |
| `github.depot` / `github.branche` | yann62155-collab/citizenconstruction / main | où lire/écrire demandes et plans |

Format des plans : voir [PLANS.md](PLANS.md).
