---
title: 'Consulter les prochaines séances Garmin Coach'
type: 'feature'
created: '2026-11-22'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 'd1a8b05762a0429ec613d3309f038048adb1a007'
context:
  - AGENTS.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problème** — Le coureur veut lire les prochaines séances de son plan Garmin Coach actif depuis le MCP.

**Approche** — Exposer `garmin_get_coach_workouts` sans argument, en lecture seule. La fenêtre va d’aujourd’hui à J+6 dans `Europe/Paris`. La réponse indique les dates demandées, la couverture par semaine et les entrées publiées par date, rattachées à leur plan.

## Boundaries & Constraints

Réutiliser la session Garmin Connect et la concordance de compte. Préserver les données valides si une semaine échoue. Signaler une absence de plan uniquement si Garmin la confirme. Les suggestions de la montre, le calendrier général, les détails d’étapes et toute mutation sont hors périmètre.

## I/O & Edge-Case Matrix

| Scénario | Entrée | Résultat attendu |
|---|---|---|
| Deux semaines | Fenêtre franchissant dimanche, avec séances publiées sur les deux semaines | Une lecture par semaine. Entrées chronologiques de J à J+6, champs de résumé disponibles et rattachement au plan. |
| Couverture partielle | La semaine suivante est vide malgré un plan actif, ou sa lecture échoue | Conserver la première semaine et signaler pour la seconde si elle est vide ou en erreur. Une date absente reste inconnue. |
| Séance ou repos | Garmin renvoie un repos, un jour de compétition et une séance associée à une activité réalisée | Préserver les indicateurs explicites de repos et de compétition. Écarter la séance réalisée. |
| Vide ou refus | Absence de plan confirmée, liste valide vide ou échec de lecture | Distinguer les trois états. Une session d’un autre compte ne déclenche aucune requête Coach. |

</frozen-after-approval>

## Code Map

- `src/main/java/io/github/trekkerdrove/garminmcp/adapter/in/mcp/WorkoutTools.java` et `application/service/GarminConnectService.java` portent les outils de la session connectée et la vérification d’activité de la Personal API.
- `application/port/out/GarminConnectPort.java` et `infrastructure/external/garmin/PythonGarminConnect.java` transportent les opérations bornées vers un seul processus Python par appel. Réutiliser la configuration des lectures de bibliothèque.
- `scripts/garmin_workout_bridge.py` vérifie le compte avant dispatch. Suivre le [lecteur Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/main/src/garmin_mcp/workouts.py#L698-L773) avec `query_garmin_graphql`, `trainingPlanScalar(calendarDate, lang: "en-US", firstDayOfWeek: "monday")`, `trainingPlanWorkoutScheduleDTOS` et `workoutScheduleSummaries`. Garder les champs utiles de Taxuspt et distinguer l’identifiant du plan, le calendrier et la séance. Si plusieurs plans sont renvoyés, appliquer le périmètre course à pied à partir des informations de plan et de sport. Borner chaque réponse à sa semaine et à la fenêtre pour éviter les doublons si Garmin renvoie deux fois la même semaine.
- `scripts/test_garmin_workout_bridge.py` et `src/test/java/io/github/trekkerdrove/garminmcp/application/service/GarminConnectServiceTest.java` fournissent les doubles de test existants.

## Tasks & Acceptance

**Execution**
- [x] `src/main/java/io/github/trekkerdrove/garminmcp/` — ajouter l’outil MCP read-only, le calcul de dates côté service, l’opération et son contrat de réponse borné.
- [x] `scripts/garmin_workout_bridge.py` — exécuter les lectures hebdomadaires et projeter les données disponibles dans le format MCP.
- [x] `scripts/test_garmin_workout_bridge.py` et `src/test/java/io/github/trekkerdrove/garminmcp/application/service/GarminConnectServiceTest.java` — couvrir la matrice et la date locale.
- [x] `README.md` — documenter l’outil et sa couverture dépendante de Garmin.

**Acceptance Criteria**
- Given a configured owner session, when the client calls the new read-only tool, then it lists the published Garmin Coach entries with their coverage for the Paris civil-date window.

## Implementation Notes

- Le pont réutilise la vérification d’activité avant toute lecture Coach. La présence d’un plan est vérifiée séparément uniquement si les deux réponses d’horaire sont vides.
- La revue a ajouté des tests du registre MCP, du pont Java et des réponses incomplètes ou incohérentes.

## Spec Change Log

## Review Triage Log

| Revue | Constat | Verdict et preuve | Suite |
|---|---|---|---|
| Blind | Repos du plan de course supprimé par un entraînement de force seul | Medium. Le sport déduit de la ligne écarte une semaine pourtant rattachée au même plan. | Patch |
| Blind | Plan multisport sans métadonnées classé course | Medium. Une ligne de course suffit dans le déducteur actuel. | Patch |
| Blind | Une ligne invalide annule la semaine | Medium. La conversion de sa date est dans le bloc d’erreur de la semaine. | Patch |
| Blind | Identifiant fourni sur la ligne écrasé par le parent | Medium. L’ordre de fusion remplace les deux champs. | Patch |
| Blind | Semaine vide malgré une séance réalisée dans la fenêtre | Medium. Le statut de couverture dépend des entrées conservées. Les dates hors fenêtre restent hors couverture. | Patch |
| Blind | Projection volumineuse annule les deux semaines | Medium. La limite actuelle s’applique à la sortie totale. | Patch |
| Blind | Délai Java annule une semaine valide | Medium. Le renouvellement normal du jeton peut prendre jusqu’à 30 s. | Patch |
| Blind | Parcours MCP et adaptateur insuffisamment testés | Medium. Les tests existants séparent le port simulé et la fonction Python. | Patch |
| Edge | Semaine de repos perdue avec une activité de renforcement | Medium. Même identifiant de plan, sport inconclusif sur une semaine. | Patch |
| Edge | Date absente ou invalide supprime les autres lignes | Medium. L’erreur de conversion est remontée à la semaine. | Patch |
| Edge | Renouvellement ou relance HTTP peut retarder le worker | Medium. La marge de 65 s ne couvre pas un renouvellement normal et les requêtes. | Patch |
| Edge | Débordement du calendrier Python en année 9999 | False. L’opération MCP sans argument reçoit la date courante du service et les six jours suivants. | Rejeté |
| Edge | Entrée sans nom ni identifiant de plan | False. Le contrat conserve les identifiants et noms fournis par Garmin. Une réponse sans référence source ne permet aucune association fiable. | Rejeté |
| Vérification | Le statut partial peut être rejeté par l’adaptateur Java | Medium. Aucun test du processus ne contrôlait la transmission du statut et de l’entrée. | Patch |
| Vérification | Plan terminé absent des fixtures | Medium. Enlever son filtre laisse passer les tests existants. | Patch |
| Vérification | Repos sans sport absent des fixtures intersemaines | Medium. Retirer le rapprochement des plans laisse passer les tests existants. | Patch |
| Vérification | La limite du worker ignore le délai de renouvellement | Medium. 30 s de renouvellement s’ajoutent aux requêtes bornées du client. | Patch |

## Verification

- `mvn -B verify` — suite Java.
- `/private/tmp/garmin-workout-python/bin/python -m unittest discover -s scripts` — suite du processus Garmin.
- Après un push normal sur `main`, vérifier le déploiement automatique Render associé au SHA et l’état du service. Appeler l’outil déployé si le compte est accessible et consigner la comparaison avec Garmin Connect si une session UI est disponible.
