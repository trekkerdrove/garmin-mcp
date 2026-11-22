# Contrat d’intégration des séances de musculation

## Outils proposés

| Outil | Entrée | Résultat |
|---|---|---|
| `garmin_search_strength_exercises` | `query` obligatoire. `limit` de 1 à 20, 10 par défaut. | Libellé et paire `category` / `exerciseName` pour chaque résultat. Recherche insensible à la casse et aux accents sur les alias connus, les clés et les paires `catégorie/clé`. Renvoie `no_data` pour une recherche sans résultat, et signale les listes tronquées. |
| `garmin_create_strength_workout` | `name`, `exercises` ordonnés et `confirm` facultatif, `false` par défaut. Chaque bloc fournit la paire d’exercice, `sets`, exactement un de `reps` ou `durationSeconds`, et éventuellement `weightKg`, `restBetweenSetsSeconds` et `restAfterBlockSeconds`. | `preview` avec le plan normalisé et les repos effectifs. Après validation, `created` ou `already_exists` avec l’ID vérifié. |

L’IA cherche les références à partir de la demande. Elle propose une sélection si le libellé désigne plusieurs variantes, puis présente le plan et demande confirmation avant écriture. Une paire invalide, y compris une clé valide dans une autre catégorie, renvoie `invalid_input` avec l’exercice concerné. Le catalogue est une source publique et sa recherche peut fonctionner sans session Garmin Connect. [Catalogue Garmin](https://connect.garmin.com/web-data/exercises/Exercises.json)

Appliquer la limite actuelle de 120 caractères au nom. Accepter au plus 50 étapes effectives, repos compris, après développement des séries, même en cas d’encodage groupé. Les séries et répétitions sont des entiers strictement positifs. Une durée de série va de 1 à 86 400 secondes et un repos de 0 à 86 400 secondes. Une charge explicite est strictement positive et plafonnée à 453,5 kg selon Garmin. Accepter au plus trois décimales pour que la valeur puisse être relue avec la précision stockée par Garmin Connect. Omettre la charge laisse le champ de charge externe vide. Si la demande est ambiguë entre charge totale et charge par haltère, clarifier la valeur à enregistrer avant la confirmation. [Limites Garmin](https://support.garmin.com/zh-HK/?faq=wZ52AaLbLG2GC1Lxu2l4k7)

`restBetweenSetsSeconds` s’applique entre deux séries du même bloc. `restAfterBlockSeconds` s’applique après sa dernière série si un autre bloc suit, même si ce bloc reprend le même exercice. Les deux valeurs sont indépendantes et valent zéro si elles sont omises. Renvoyer `invalid_input` pour un repos positif entre séries dans un bloc d’une seule série ou une transition positive après le dernier bloc.

Les blocs gardent l’ordre reçu. Répéter une paire d’exercice dans plusieurs blocs permet de faire varier les répétitions, la durée ou la charge. Utiliser un bloc par série si chacune a un réglage différent. L’aperçu montre les valeurs par série et l’emplacement exact des repos.

## Exemple de demande normalisée

```json
{
  "name": "Musculation test",
  "exercises": [
    {"category":"SQUAT","exerciseName":"BARBELL_BACK_SQUAT","sets":2,"reps":8,"weightKg":60,"restBetweenSetsSeconds":90,"restAfterBlockSeconds":90},
    {"category":"SQUAT","exerciseName":"BARBELL_BACK_SQUAT","sets":1,"reps":6,"weightKg":65,"restAfterBlockSeconds":120},
    {"category":"PUSH_UP","exerciseName":"PUSH_UP","sets":2,"reps":12,"restBetweenSetsSeconds":60}
  ]
}
```

Le plan comporte cinq séries séparées, dans l’ordre, par 90 s, 90 s, 120 s et 60 s de repos. Les 120 s séparent le dernier squat de la première série de pompes. Les pompes sont prévues sans charge externe.

## Correspondance Garmin et réutilisation

| Élément | Règle d’intégration |
|---|---|
| Sport | `strength_training` avec `sportTypeId=5` sur la séance et son segment. |
| Série | Étape exécutable. Répétitions avec `reps` / 10 ou durée avec `time` / 2. Garder la catégorie et la clé officielle de l’exercice. |
| Repos | Étape temporisée avec durée en secondes. Si un groupe ajouterait un repos après sa dernière série, développer les séries en étapes explicites pour garder la durée exacte de la transition. Vérifier l’ordre et la durée de chaque repos à la relecture. |
| Répétitions de séries | Taxuspt utilise `RepeatGroupDTO` et `iterations` / 7. Un groupe peut être utilisé si les repos effectifs correspondent au plan. Des étapes explicites conviennent aussi. Vérifier le nombre de séries exécutables et l’ordre des étapes. |
| Charge | L’exemple bas niveau Taxuspt utilise `weightValue` et `weightUnit` en kilogrammes. Vérifier la valeur et l’unité stockées avec une charge décimale lors du test réel. |

Le format est inspiré du [constructeur de Taxuspt](https://github.com/Taxuspt/garmin_mcp/blob/main/src/garmin_mcp/workout_builders.py#L400) et de sa [documentation de l’écriture de musculation](https://github.com/Taxuspt/garmin_mcp/blob/main/src/garmin_mcp/workouts.py#L934). Le constructeur haut niveau de référence couvre les répétitions et les repos. La charge vient de son format bas niveau. Les paires d’exercice sont prises dans le catalogue Garmin actuel.

Réutiliser `WorkoutTools`, `GarminConnectService` et le processus `scripts/garmin_workout_bridge.py` pour les écritures. `save_copy` et `read_workout` offrent les points de réutilisation pour l’enregistrement avec dédoublonnage par nom et la relecture. Comparer les exercices, charges normalisées, répétitions, durées et repos dans l’ordre effectif, indépendamment du découpage en blocs. Utiliser le même transport authentifié `POST /workout-service/workout` et `GET /workout-service/workout/{id}` sur l’API interne Garmin Connect. La Personal API conserve son rôle de lecture et de contrôle de compte.

Le contenu identique réutilise l’ID. Une divergence avec une séance existante du même nom renvoie `workout_name_conflict`. Après une écriture dont la relecture est absente ou divergente, renvoyer `workout_write_unconfirmed`. Avant tout nouvel envoi, rechercher la séance par son nom.

## Vérification de livraison

| Cas | Observation attendue |
|---|---|
| Exercice exact, ambigu, inconnu et paire invalide | Résultats bornés et distincts. Aucune écriture avant choix et confirmation. |
| Aperçu de séance | Séries et repos effectifs visibles, y compris un exercice sans charge. Exécuter l’exemple avec trois blocs. Vérifier que les repos impossibles sont refusés et que l’aperçu n’écrit rien. |
| Séance de test confirmée | Recherche et détail par ID. Vérifier la classification musculation, l’ordre, les identifiants d’exercices, des répétitions et charges variables pour un même exercice, une charge de 62,5 kg, une étape chronométrée et un exercice sans charge. Vérifier le repos de transition distinct du repos entre séries et l’absence de repos final. |
| Rejeu et conflit | L’appel identique conserve l’ID et une seule occurrence en bibliothèque. Modifier uniquement une transition tout en gardant le nom déclenche un conflit. |
| Régression | Les anciens arguments d’une séance de course avec allure, ainsi que ceux des outils de marche et de vélo, gardent leurs résultats. Le mode sans écriture masque l’outil de création. |

Une validation sur montre compatible consiste à transférer la séance, synchroniser puis contrôler la liste des séries et les repos affichés sur l’appareil. La validation côté serveur se termine sur la relecture Garmin Connect. [Compatibilité Garmin](https://support.garmin.com/fr-FR/?faq=lLvhWrmlMv0vGmyGpWjOX6&tab=topics) · [Transfert vers la montre](https://support.garmin.com/fr-BE/?faq=Oyqt6jUjOF8L1Rnuc9Sms8)
