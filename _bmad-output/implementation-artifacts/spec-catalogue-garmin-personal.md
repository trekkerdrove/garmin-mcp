---
title: Livrer le catalogue de la Garmin Connect Personal API
type: feature
created: '2026-11-21'
status: done
baseline_commit: c5140d39f07971d3c542a1fc11a8594848f6ac33
route: dispatch
review_loop_iteration: 0
context:
  - _bmad-output/specs/spec-garmin-mcp-tools/SPEC.md
  - _bmad-output/specs/spec-garmin-mcp-tools/catalogue-outils.md
  - _bmad-output/specs/spec-garmin-mcp-tools/contrats-transverses.md
---

<frozen-after-approval reason="Périmètre confirmé par le propriétaire">

## Intent

Livrer d'une traite les trois lots du catalogue approuvé sur la branche codex/garmin-mcp-tools et y pousser le résultat. Le propriétaire peut consulter les sept capacités via les 36 outils nommés du catalogue, sonde comprise.

## Boundaries & Constraints

Conserver l'accès propriétaire, le contrat de la sonde, les dates civiles locales et les appels de lecture documentés. Renvoyer des mesures projetées avec unités, disponibilité et limites. Les fichiers FIT sont bornés et traités en mémoire. Les secrets et les réponses brutes restent privés.

## I/O & Edge-Case Matrix

| Situation | Entrée | Résultat |
|---|---|---|
| Semaine de course | Pages avec segments multisport | Kilométrage dédupliqué |
| Repos et entraînement | Nuits absentes ou panne | Couverture mesurée ou comparaison indisponible |
| Pesées ou repas multiples | Une date et un filtre | Toutes les mesures concernées dans les limites indiquées |
| Appel anonyme ou arguments invalides | Requête MCP | Aucun accès Garmin |
| Archive sans capteur ou invalide | Activité FIT | État explicite, ressources libérées |

</frozen-after-approval>

## Code Map

- GarminHttpClient possède l'échange du jeton Garmin et le transport REST. McpSecurity protège le point d'entrée.
- GarminConnectionTool et McpIntegrationTest fournissent l'annotation et le serveur Garmin simulé déjà utilisés par la sonde.
- Les projections de l'API appartiennent aux adaptateurs externes. Le calcul des agrégats et la sélection des données restent testables via des ports.

## Tasks & Acceptance

- [x] `infrastructure/external/garmin/GarminHttpClient.java` et `application/port/out/` — mutualiser les GET autorisés, erreurs et limites.
- [x] `adapter/in/mcp/` et `application/service/` — implémenter les vues activités, sommeil, santé, performance et suivis personnels spécifiées.
- [x] `pom.xml` et `infrastructure/external/garmin/` — décoder les messages FIT, limiter les entrées, la mémoire, le temps et la sortie.
- [x] `src/test/java/` et `README.md` — vérifier le catalogue, les calculs, les erreurs et documenter l'usage.

- Étant donné un propriétaire authentifié, quand il liste les outils, alors les 36 noms et leurs annotations de lecture seule correspondent au catalogue.
- Étant donné les réponses Garmin fictives, quand il appelle les outils, alors les résultats distinguent mesures, absences, échecs et troncatures.
- Étant donné des archives malformées ou simultanées, quand le serveur les reçoit, alors les limites s'appliquent et la sonde reste disponible.

## Implementation Notes

Les trois lots sont enregistrés ensemble sur le serveur sans état. La vérification HTTP compare le catalogue aux 36 noms attendus, appelle chaque outil et couvre les 28 opérations de lecture avec un jeton OAuth propriétaire et un serveur Garmin fictif. Les ports de lecture isolent les calculs des réponses Garmin. Le SDK Java FIT 21.212.0 décode les fichiers en mémoire.

Le test de charge FIT couvre six heures à 1 Hz sous Linux amd64 avec une limite conteneur de 512 Mio. Le pic mesuré de 115,3 Mio concerne le processus de test du décodeur. La vérification du compte personnel et du déploiement Render relève d'un essai ultérieur.

## Spec Change Log

## Review Triage Log

| Revue | Verdict | Vérification ou correction |
| --- | --- | --- |
| Activités, projections A1 | medium | Les zones, exercices et courbes sont projetés uniquement pour les vues qui les utilisent. |
| Performance sans filtre | medium | A1 suffit pour les meilleurs efforts ou agrégats sans filtre de sport. Le service consulte la hiérarchie pour les calculs qui en dépendent. |
| Objectifs sans mesure | medium | Les objectifs des tendances et résumés sont conservés séparément de la couverture des mesures. |
| Doublons hebdomadaires | high | Déduplication par date et catégorie pertinente. Des valeurs contradictoires rendent la lecture indisponible. |
| Panne de l’enrichissement VFC | medium | Une défaillance isolée laisse la FC indépendante accessible. Le quota et le refus d’accès arrêtent les lectures. |
| Fenêtre respiratoire interrompue | medium | Le résultat garde les fenêtres déjà enrichies et précise la plage en échec. |
| Couverture stress | medium | La provenance et les dates inconnues distinguent une panne de la source d’enrichissement d’une absence. |
| Puissance FIT partiellement renseignée | medium | La synthèse des records indique sa portée activité et sa méthode, même quand une séance ne fournit que le travail. |
| Couverture FIT des tours et séries | medium | Les horodatages des tours et séries alimentent la couverture quelle que soit la vue. |
| Poids antérieur à un an | medium | H17 fournit la mesure précédente à la date de l’activité. La borne artificielle a été retirée. |
| Détails de cycle tronqués | medium | Une liste supérieure à 32 éléments propage le statut de troncature au client. |
| Archive HTTP surdimensionnée | medium | Une limite identique s’applique au corps HTTP et au décodeur, en transfert de longueur connue ou segmenté. |
| Attentes du test HTTP | medium | Les 35 nouveaux outils ont un statut attendu déterminé par leur fixture. Les principales mesures sont contrôlées. |
| Lien du catalogue | low | La livraison inclut le catalogue cité par le README et ses sources relatives. |
| Nuit et date inconnue | high | Les jointures exigent une date Garmin vérifiée. Les tests couvrent H7, H8 et H11. |
| FC quand la VFC échoue | medium | La défaillance est désormais rattachée à la source VFC et permet la lecture FC sous réserve du quota. |
| Statut VFC NONE | medium | Les statuts et objectifs seuls sont conservés mais comptent zéro jour mesuré. |
| Doublons dans les moyennes | high | Déduplication avant les agrégats d’intensité, pas, stress et VFC. |
| Troncature du cycle | medium | Le plafond par liste est indiqué dans le schéma MCP. |
| Date requise du repas | high | Le journal doit porter la date attendue pour participer au résultat. |
| Pesée la plus récente éligible | medium | Le test accepte une pesée passée même ancienne et exclut les mesures postérieures à l’activité. |
| Taxonomie incomplète | high | Une relation de famille indéterminée ne donne plus un total de course complet. |
| Casse de sortOrder | low | Les directions sont normalisées avec Locale.ROOT avant le contrôle de validité. |
| Puissance moyenne absente d’une séance | medium | Les records restent disponibles et portent une provenance distincte de la séance. |
| Répertoire central du ZIP | medium | Les en-têtes locaux et centraux, noms, tailles et CRC sont recoupés dans les bornes de l’archive. |
| 404 de l’échange de jeton | medium | Une erreur de l’endpoint de jeton est classée comme indisponibilité. Le 404 de la ressource reste distinct. |
| Filtres HTTP/pagination | medium | Un appel MCP vérifie les paramètres répétés, le texte contenant une esperluette, l’offset et les identifiants des pages. |
| Limite des transferts segmentés | medium | Le test MCP dépasse 4 Mio sans Content-Length. Le client ferme le flux pour empêcher Spring de vider le corps restant. |
| Séance bien-être A6 | medium | La fixture A6 vérifie date locale, moyenne cardiaque et durée de zone en millisecondes. |
| Signe de la dérive cardiaque | medium | Une série irrégulière vérifie les deux ratios et une variation négative non nulle. |
| Erreur de la route de jeton | medium | Le code conserve les réponses de refus d’accès et ne transforme pas l’absence de l’endpoint de jeton en activité absente. |

## Verification

Le build Maven propre et le build Docker Java 21 linux/amd64 exécutent chacun 51 tests sans échec. Le conteneur complet démarre sans clé avec 512 Mio et un CPU, annonce 36 outils, répond sur /health et refuse l’accès MCP anonyme. Le pic au démarrage et pendant cette sonde est de 337 494 016 octets. Les scripts BMad passent 67 tests et quatre sous-tests dans leur environnement de distribution.
