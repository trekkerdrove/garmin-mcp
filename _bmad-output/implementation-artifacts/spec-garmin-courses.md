---
title: 'Bibliothèque de parcours Garmin Connect'
type: 'feature'
created: '2026-11-22'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'd1a8b05762a0429ec613d3309f038048adb1a007'
context:
  - /Users/constantvennin/garmin-mcp/_bmad-output/specs/spec-garmin-courses/SPEC.md
  - /Users/constantvennin/garmin-mcp/_bmad-output/specs/spec-garmin-courses/contrat-outils.md
  - /Users/constantvennin/garmin-mcp/_bmad-output/specs/spec-garmin-courses/formats-et-traces.md
  - /Users/constantvennin/garmin-mcp/_bmad-output/specs/spec-garmin-courses/integration-et-recette.md
---

<frozen-after-approval reason="human-owned intent">

## Intent

Le propriétaire demande à créer, retrouver et gérer ses parcours Garmin par conversation. Implémenter les neuf outils et les formats de la spec approuvée. Préserver les développements parallèles dans un checkout isolé.

## Boundaries & Constraints

Réutiliser Java, Spring AI, l’authentification du propriétaire, le SDK FIT et la session Connect du bridge existant. Séparer lectures, planification et écritures configurées. Écritures sur aperçu confirmé, relecture et reprise sans rejeu incertain. Garmin reste la source de vérité. Les capacités Garmin non validées restent conditionnelles avant activation. La recette automatisée utilise des réponses simulées et des fichiers de test. L’envoi à la montre relève de son lot dédié.

## I/O & Edge-Case Matrix

| Scénario | Entrée | Résultat |
| --- | --- | --- |
| Planification et import | Boucle ou étapes, GPX, TCX, FIT, GeoJSON, points, activité | Aperçu fidèle avant création privée et relecture après confirmation |
| Bibliothèque | Filtres, identifiant, révision, export paginé | Résumé, tracé ou fichier reconstructible, contrôle de propriété |
| Modification | Nom, métadonnées, tracé, points | Même identifiant, préservation des champs omis, conflit révisable |
| Reprise | Confirmations concurrentes, délai amont, 429 de traitement | Une exécution par aperçu, statut explicite, réconciliation sans nouvelle mutation incertaine |

</frozen-after-approval>

## Code Map

- Travailler dans le checkout qui contient ce fichier. Le checkout source absolu utilisé dans context est en lecture seule pour cette implémentation.

- `PythonGarminConnect`, `GarminConnectConfiguration` et `scripts/garmin_workout_bridge.py` partagent session, rapprochement Personal API et processus isolé avec budgets distincts.
- `GarminArchive`, `GarminFitDecoder`, SDK FIT et Jackson offrent les entrées format. `McpSecurity` protège déjà toutes les opérations MCP.
- `WorkoutTools`, `ToolResult` et `McpIntegrationTest` fixent les conventions. `Dockerfile` embarque le bridge et sa dépendance Python.

## Tasks & Acceptance

- [x] `application/dto/CourseData.java` et ports parcours — contrat interne typé.
- [x] `infrastructure/external/garmin/CourseFormats.java` — parseurs bornés, métadonnées, FIT et exports.
- [x] `scripts/garmin_courses_bridge.py` et bridge existant — liste propriétaire, CRUD, export, relecture et concurrence partagée.
- [x] `infrastructure/external/garmin/CourseRouting.java` — ORS et Pelias configurables avec projection du résultat.
- [x] `application/service/CourseService.java` — opérations, révisions, cache borné, réconciliation et export.
- [x] `adapter/in/mcp/CourseTools.java`, configuration et tests — catalogue HTTP, bascules, limites, recette simulée et régressions.

**Acceptance Criteria**

- Given chaque source compatible, when elle est prévisualisée puis confirmée, then la géométrie vérifiée et le lien Garmin sont rendus.
- Given une cible du compte, when elle est renommée, copiée, exportée puis supprimée, then identité, versions, intégrité des octets et absence finale sont vérifiées.
- Given une confirmation répétée ou une écriture incertaine, when le client consulte son statut, then aucune seconde mutation incertaine n’est envoyée.
- Given le module désactivé, when le client consulte le catalogue, then les outils existants gardent leur comportement.

## Implementation Notes

- 74 tests Java et 28 tests Python passent. La matrice est couverte par CourseServiceTest, CourseRoutingTest, CourseMcpIntegrationTest et test_garmin_courses_bridge.
- Les contrôles utilisent des données simulées. Les bascules Garmin restent à false. Les champs au-delà du renommage, points Garmin et FIT natif dépendent de la matrice de recette. Un TCX reconstruit nécessite des timestamps.

- Après revue, 81 tests Java et 33 tests Python passent. Vérifications ajoutées pour les curseurs, les timestamps, la session, les quotas, la conservation de données Garmin et les budgets d’export.
- Recette Garmin Connect, fournisseur cartographique et appareil à effectuer avant activation réelle. Les changements contemporains dans Garmin restent soumis aux garanties de l’API amont.

## Spec Change Log

## Review Triage Log

| Avis | Verdict | Suite et preuve |
| --- | --- | --- |
| Blind · timestamps de navigation | high | Corriger la comparaison des représentations ISO après le passage Python. |
| Blind · pagination | high | Corriger le coût cumulé de chaque curseur démontré sur 2 000 lignes. |
| Blind · export natif | medium | Séparer les budgets d’import, de validation d’export et d’enveloppe base64. |
| Blind · nom omis | high | Conserver exactement le nom Garmin lors de l’édition d’un autre champ. |
| Blind · paramètres Garmin | high | Comparer les réglages et notes non modifiés après le PUT. |
| Blind · zéro signé | medium | Normaliser la distance zéro avant la recherche de point. |
| Blind · précontrôle 429 | medium | Conserver le délai et la possibilité de reprendre avant envoi. |
| Blind · extensions GPX | medium | Signaler les extensions de waypoints racine. |
| Blind · points explicites | high | Rejeter une structure invalide avant d’appliquer la politique de pertes. |
| Blind · segments copiés | medium | Signaler l’aplatissement lors de la duplication. |
| Blind · avertissements ORS | medium | Conserver les avertissements bornés par proposition. |
| Blind · délai des homonymes | high | Borner la vérification cumulée des candidats. |
| Edge · timestamps de navigation | high | L’égalité Java échoue pour une même date sérialisée avec Z et +00:00. |
| Edge · zéro signé | medium | Le point initial est accessible par la distance −0.0. |
| Edge · pagination | high | Le cache sature alors que la liste est complète et sous le seuil accepté. |
| Edge · nom omis | high | Un nom Garmin existant ne repasse pas par la normalisation client. |
| Edge · altitudes enrichies | medium | L’équivalence stricte entre contenus reste la règle de précontrôle. Quand le même aperçu a réussi une écriture, sa confirmation retourne le résultat précédent. Une nouvelle demande dont les altitudes restent absentes peut différer du parcours Garmin enrichi. |
| Edge · candidat disparu | medium | L’aperçu peut être repris sans mutation après ce conflit de lecture. |
| Edge · plans partiels | medium | Valider et réserver toutes les propositions avant de conserver leurs références. |
| Edge · FIT natif >4 Mio | medium | Un FIT valide reste adressable avec la limite d’export appropriée. |
| Edge · GeoJSON null | medium | Le document sans objet doit renvoyer invalid_input. |
| Edge · GeoJSON singleton | medium | Une ligne GeoJSON de moins de deux positions doit être refusée. |
| Edge · métriques d’origine | medium | Retirer les alias de distance et dénivelé lors du remplacement du tracé. |
| Edge · échec de sauvegarde session | high | Garder le résultat Garmin malgré un échec postérieur de persistance du jeton. |
| Edge · dossier session en lecture seule | high | Un verrou hors de ce dossier protège le comportement historique des séances. |
| Edge · course changé côté Garmin entre GET et écriture | maybe-false | La révision est relue avant l’envoi. La présence d’une précondition amont doit être établie sur la route Connect avant de promettre une exclusion de cette course. |
| Edge · géométrie complète après confirmation | false | Le contrat précise que la géométrie complète est accessible à la demande. Le succès résume le parcours relu. |
| Verification · lancement du bridge | medium | Couvrir le passage du mode courses et la projection horodatée à la frontière. |
| Verification · transport 429 réel | medium | Tester les deux réponses brutes et les deux formats Retry-After. |
| Verification · catalogue lecture seule | medium | Vérifier le câblage des lectures et des mutations dans ce profil. |
| Verification · timestamps de navigation | high | Le contre-exemple entre .123Z et .123000+00:00 a été exécuté. |
| Verification · pagination | high | Le contre-exemple s’arrête à 980 lignes. |
| Verification · sauvegarde session | high | Un os.replace en erreur après acceptation transforme le résultat en garmin_unavailable. |
| Revue principale · sport gravel | high | L’ID 4 correspond à Other dans le mapping Garmin documenté. Employer 143 pour gravel. |

## Verification

- `mvn -o test` pour Java et les contrats MCP.
- `python3 -B -m unittest discover -s scripts -p 'test_*.py'` pour le bridge.
- Recette Garmin dédiée requise avant activation réelle.
