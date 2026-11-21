---
id: SPEC-garmin-mcp-tools
companions:
  - catalogue-outils.md
  - contrats-transverses.md
  - ../../planning-artifacts/architecture/architecture-garmin-mcp-2026-11-21/ARCHITECTURE-SPINE.md
  - ../../planning-artifacts/research/technical-garmin-personal-api-taxuspt-2026-11-21/research.md
sources:
  - ../../implementation-artifacts/spec-premier-appel-garmin.md
---

# Étendre le catalogue Garmin MCP

## Pourquoi

Le propriétaire du compte peut déjà vérifier la connexion Garmin. Il doit pouvoir interroger ses activités, son sommeil, sa récupération et les autres données disponibles depuis le même client MCP. La Personal API permet de couvrir un ensemble étendu d’usages de lecture avec des outils ciblés, y compris les entraînements associés à une activité et les séances bien-être sur une période.

## Capacités

- **CAP-1 · Kilométrage hebdomadaire**
  - **intent** — Connaître la distance et le nombre de courses sur une semaine.
  - **success** — Sur une semaine paginée contenant une activité multisport et des doublons, `running_distance` retourne les dates et le total attendu, chaque distance comptée une seule fois.
- **CAP-2 · Sommeil après entraînement ou repos**
  - **intent** — Comparer la durée du sommeil principal entre les jours d’entraînement et de repos.
  - **success** — `compare_sleep_by_training_day` rattache chaque nuit au jour précédant le réveil et renvoie la moyenne et la couverture de chaque groupe. L’écart est disponible quand les deux groupes sont mesurés.
- **CAP-3 · Activités enregistrées**
  - **intent** — Rechercher des activités et lire leurs mesures, segments, entraînements associés, zones, exercices et séances bien-être.
  - **success** — Un client MCP retrouve une activité avec des filtres documentés, peut avancer dans les pages et voit les mesures disponibles pour l’identifiant sélectionné.
- **CAP-4 · Bien-être et récupération**
  - **intent** — Consulter les mesures du jour et les tendances de sommeil, VFC, fréquence cardiaque, mouvement, stress, Body Battery, respiration, SpO₂ et hydratation.
  - **success** — Une plage traversant une limite Garmin retourne les dates et unités attendues. Les jours mesurés, les jours sans données et les lectures en échec sont distingués.
- **CAP-5 · Performance et charge observée**
  - **intent** — Suivre les effets des séances, les zones configurées, le VO₂ max, l’âge de forme, les meilleurs efforts et les agrégats d’entraînement.
  - **success** — Un jeu d’activités datées retourne les agrégats et meilleurs efforts attendus avec la période et leur provenance. Les valeurs d’activité restent identifiables face aux mesures du profil.
- **CAP-6 · Suivis personnels**
  - **intent** — Consulter les pesées et objectifs associés, le journal nutritionnel, le suivi du cycle et les préférences du compte.
  - **success** — Une plage de pesées garde les mesures prises plusieurs fois le même jour. Le filtrage d’un repas et les vues du cycle renvoient uniquement les données au périmètre demandé.
- **CAP-7 · Analyse FIT**
  - **intent** — Examiner des échantillons enregistrés et des mesures dérivées d’une activité lorsque le fichier original le permet.
  - **success** — Un FIT représentatif renvoie des résultats bornés. Une archive sans FIT, illisible ou au-delà des limites renvoie un état explicite et le serveur reste disponible.

## Contraintes

- **200 outils MCP au total maximum**, sonde comprise. La cible de livraison est **36 outils**, dont **35 ajouts** dans le [catalogue](catalogue-outils.md).
- Un seul serveur `/mcp` en Streamable HTTP sans état de session. Les appels Garmin sont réservés au propriétaire authentifié. La sonde `garmin_check_connection` conserve son contrat.
- Les sources de données passent par les lectures documentées de la Garmin Connect Personal API. L’échange clé contre jeton reste côté serveur.
- Les résultats respectent les [contrats transverses](contrats-transverses.md) de dates civiles, couverture, unités et limites. Pas de stockage métier, de réponse Garmin brute ni de secret exposé.
- Le serveur reste compatible avec le service Render Free et son budget mémoire décrit dans l’architecture.

## Hors périmètre

- Écritures Garmin, bibliothèque et planification de séances, inventaire de parcours ou d’appareils, et lecture via les API privées de Garmin Connect.
- Parité des noms ou formats Taxuspt, scores propriétaires non documentés, diagnostic ou reproduction de leur estimation TDEE.
- Téléchargement de fichiers par MCP, dépôt de fichiers sur le serveur et export GPX, TCX ou CSV.

## Critère de réussite

`tools/list` annonce exactement les outils livrés du catalogue, chacun une fois, avec le plafond vérifié. Sur données Garmin simulées, le propriétaire peut appeler les outils des sept capacités et obtenir des sorties conformes. Un client sans autorisation ou une demande invalide ne déclenche aucun appel Garmin.

## Hypothèses

- Les deux outils nommés dans l’architecture gardent leur nom. Les autres ajouts utilisent le préfixe `garmin_`.
- Le catalogue privilégie les usages distincts. Les variantes d’un même besoin passent par des paramètres bornés.

## Questions ouvertes

- Quels champs sont réellement renseignés sur le compte et ses appareils, et quelles unités Garmin emploie pour les moyennes de poids sans unité dans le schéma ?
- Quelles limites de téléchargement et de décodage FIT passent sur Render avec des fichiers représentatifs et plusieurs appels concurrents ?
