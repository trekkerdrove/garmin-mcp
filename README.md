# garmin-mcp

Serveur Spring Boot MCP pour un compte Garmin Connect personnel. Le catalogue expose **37 outils de lecture** pour interroger les activités, la santé, la performance, les suivis personnels et les fichiers FIT du compte. Le [catalogue de la Personal API](_bmad-output/specs/spec-garmin-mcp-tools/catalogue-outils.md) décrit les outils qui s’appuient sur ses endpoints. La recherche d’exercices de musculation utilise le [catalogue public Garmin](https://connect.garmin.com/web-data/exercises/Exercises.json).

| Exemple de question | Outil | Arguments MCP |
| --- | --- | --- |
| Quelle distance ai-je couru la semaine dernière ? | `running_distance` | `{}` |
| Est-ce que je dors plus après une journée avec entraînement ? | `compare_sleep_by_training_day` | `{}` pour les 28 derniers jours terminés |
| Quelles pesées ai-je enregistrées le 1er avril ? | `garmin_get_weight` | `{"startDate":"2025-04-01"}` |
| Quels échantillons de puissance cette activité contient-elle ? | `garmin_get_fit_samples` | `{"activityId":42,"metrics":["powerW"],"limit":50}` |

Le client découvre les paramètres, les unités disponibles et les plafonds via `tools/list`. Les outils renvoient `status` et `data`. Les valeurs `ok`, `no_data` et `truncated` indiquent respectivement une lecture exploitable, une absence de mesure ou une lecture limitée. Une limitation de débit Garmin renvoie `garmin_rate_limited`. La sonde historique `garmin_check_connection` renvoie toujours son statut et l’indicateur `hasActivity`.

La semaine de course va du lundi au dimanche dans `Europe/Paris`. La comparaison de sommeil rattache la nuit au jour précédant le réveil. Les pesées et les calculs W/kg utilisent des kilogrammes. Sur les routes de pesée, Garmin documente le kg mais peut renvoyer une valeur en grammes sans préciser l’unité. Le serveur divise par 1 000 les poids individuels bruts d’au moins 1 000 et conserve les valeurs inférieures. Les objectifs utilisent les grammes indiqués par Garmin. Pour les analyses FIT, le serveur accepte une archive à la fois, de 4 Mio compressés et 8 Mio décompressés au maximum. Une requête concurrente reçoit `fit_busy`. Le serveur expose au plus 200 lignes par analyse FIT et garde les fichiers en mémoire pour la durée de l’appel.

## Lancer en local

Java 21 et Maven sont nécessaires. Lance `mvn verify` pour vérifier le serveur avec des
données fictives, puis démarre-le sur ton Mac.

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

La sonde répond sur `http://localhost:8080/health`.

Spring charge le fichier `.env` local à la racine au format `NOM=valeur`. Git et le build Docker
excluent ce fichier. Mets ta clé personnelle dans `GARMIN_API_KEY`.

| Variable | Valeur |
| --- | --- |
| `GARMIN_API_KEY` | Clé de la Garmin Connect Personal API. |
| `MCP_PUBLIC_URL` | URL HTTPS complète du serveur, par exemple `https://garmin-mcp.onrender.com/mcp`. |
| `MCP_OAUTH_ISSUER` | Valeur exacte du champ `issuer` publié par le fournisseur OAuth. |
| `MCP_OAUTH_OWNER_SUBJECT` | Identifiant stable `sub` du compte autorisé chez ce fournisseur. |

`/health` reste disponible sans configuration OAuth. Tous les appels au MCP exigent un jeton
valide pour l’audience `MCP_PUBLIC_URL` et le sujet configuré.

Pour appeler l’outil depuis le Mac, configure les variables OAuth et obtiens un jeton d’accès
pour ce sujet et cette audience auprès de ton fournisseur. Place ce jeton dans la variable
de session `MCP_ACCESS_TOKEN`, puis envoie un appel à ton serveur local.

```bash
printf 'Authorization: Bearer %s\n' "$MCP_ACCESS_TOKEN" |
  curl -sS http://localhost:8080/mcp --header @- \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-11-25' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"garmin_check_connection","arguments":{}}}'
```

## Construire le conteneur pour Render

```bash
docker build -t garmin-mcp .
docker run --rm -p 127.0.0.1:8080:10000 garmin-mcp
```

Pour transmettre ton fichier local au conteneur, ajoute `--env-file .env` avant le nom de l’image.

Le service lit le port `PORT` fourni par Render. Configure `/health` comme sonde HTTP et
les quatre variables dans **Environment**. Pour WorkOS AuthKit, active CIMD et déclare
`MCP_PUBLIC_URL` comme *Resource Indicator*. Dans ChatGPT web, enregistre cette URL comme
serveur MCP personnalisé et sélectionne **OAuth**.

## Saisir ou corriger une pesée

`garmin_set_weight` prend un poids en kg et une date `YYYY-MM-DD`. La date par défaut est aujourd’hui dans `Europe/Paris`. Pour une nouvelle pesée, l’heure par défaut est minuit. Fournis `time` au format `HH:mm` si l’heure de la mesure est connue.

Appelle d’abord l’outil sans `confirm` pour voir l’aperçu. Si plusieurs pesées existent à cette date, `selection_required` renvoie les heures, les valeurs et les identifiants. Choisis la pesée, recopie sa valeur `selection` et confirme l’opération avec `confirm: true`. Une correction conserve l’heure de la pesée manuelle et vérifie la nouvelle valeur avant de retirer l’ancienne. L’outil protège les mesures de balance et les entrées de composition corporelle. `weight_cleanup_pending` indique que le remplacement a été relu et que la suppression de l’ancienne mesure reste à vérifier. Répète le même appel avec la même valeur `selection` pour terminer la correction. Si l’écriture reste incertaine (`weight_write_unconfirmed`) ou si l’ancienne mesure a changé, relis la journée avant toute nouvelle confirmation.

L’outil requiert la session Garmin Connect déjà utilisée pour gérer les entraînements. `garmin_get_weight` lit les mesures par la Personal API.

## Gérer les entraînements

`garmin_create_workout` enregistre une séance dans la bibliothèque Garmin Connect. Il accepte la course, la marche et le vélo, avec 1 à 50 étapes de durée ou de distance. Chaque étape peut avoir une cible de fréquence cardiaque ou, en course et en marche, une cible d’allure. Les bornes `fast` et `slow` s’expriment en secondes par kilomètre. Pour une allure unique de 4 min 30 s/km, donne 270 aux deux bornes. Le premier appel prévisualise les étapes. Passe `confirm: true` après validation pour enregistrer la séance et la relire. Une séance identique portant le même nom est réutilisée. Pour une séance différente, choisis un autre nom.

```json
{
  "name": "Fractionné 400 m",
  "sport": "running",
  "steps": [
    {"kind": "warmup", "durationSeconds": 600},
    {"kind": "interval", "distanceMeters": 400, "paceSecondsPerKm": {"fast": 265, "slow": 275}},
    {"kind": "cooldown", "durationSeconds": 300}
  ]
}
```

| Outil | Appel de prévisualisation |
| --- | --- |
| `garmin_update_workout` | `{"workoutId":123,"name":"Nouveau nom"}`. Fournis `steps` pour remplacer toutes les étapes d’une séance mono-segment de course, marche ou vélo. Les champs omis sont conservés. |
| `garmin_delete_workout` | `{"workoutId":123}` renvoie le nom et l’ID de la séance ciblée. La suppression est définitive. |

Pour retrouver une séance, appelle `garmin_search_workouts` avec `{"query":"fractionné 400"}`. Les résultats sont triés par date de dernière modification, et la recherche ignore les accents et la casse. `garmin_get_workout` renvoie le détail et l’ordre des étapes, y compris les groupes de répétitions.

Pour dupliquer une séance, appelle `garmin_duplicate_workout` avec `{"workoutId":123,"name":"Fractionné 600 m","distanceChanges":[{"stepOrder":2,"distanceMeters":600}]}`. Omettre `distanceChanges` copie la séance sous son nouveau nom. Chaque modification doit correspondre à une seule étape de distance. Utilise l’ordre renvoyé par `garmin_get_workout`. La confirmation crée une séance avec son propre ID. Une copie identique déjà présente sous le même nom est réutilisée.

Après vérification de l’aperçu, ajoute `"confirm":true` aux mêmes arguments. La modification conserve l’ID. Le serveur relit la séance après écriture et vérifie sa disparition après suppression. Un ID absent renvoie `workout_not_found`. Si la vérification échoue après l’envoi de l’écriture, le statut `workout_write_unconfirmed` signale que l’état final reste à vérifier.

Pour la musculation, `garmin_search_strength_exercises` renvoie les références du catalogue Garmin et fonctionne sans session Garmin Connect. `garmin_create_strength_workout` accepte des blocs ordonnés avec exercice, séries, répétitions ou durée, charge en kg et deux repos indépendants. Par exemple, deux séries de huit squats à 62,5 kg avec 90 s entre les séries, puis 120 s avant le gainage. Le premier appel montre chaque série et chaque repos. Après confirmation, l’outil relit la séance dans Garmin Connect. Un rejeu identique renvoie le même ID. [Contrat de musculation](_bmad-output/specs/spec-garmin-strength-workouts/contrat-musculation.md)

Avec les outils de bibliothèque, la création de séances de musculation, la saisie du poids et la lecture Garmin Coach, le catalogue compte 46 outils quand `GARMIN_WORKOUT_WRITES_ENABLED=true`. Génère une session en Terminal avec `uv run --script scripts/prototype_garmin_workout.py login --token-dir ~/.garmin-connect-workouts` sur ton poste. En local, installe `garminconnect==0.3.2` dans un environnement Python et indique son exécutable avec `GARMIN_WORKOUT_PYTHON`. Renseigne `GARMIN_CONNECT_TOKEN_DIR` avec le dossier privé de la session. Le serveur compare une activité récente à la Personal API avant d’accéder aux données Garmin Connect pour vérifier que les deux accès visent le même compte.

L’image Docker embarque le client Python. Sur Render, ajoute le fichier de la session dédiée aux **Secret Files**, puis règle `GARMIN_CONNECT_SESSION_FILE=/etc/secrets/garmin_tokens.json` et active l’écriture. Le serveur garde une copie privée pour renouveler la session pendant son exécution. Le système de fichiers de Render Free est éphémère. Remplace le fichier secret si Garmin refuse la session après un redémarrage. L’adaptateur utilise l’API interne Garmin Connect, dont les réponses et le mécanisme de connexion peuvent évoluer.

## Consulter les prochaines séances Garmin Coach

Appelle `garmin_get_coach_workouts` sans argument. L’outil lit les entrées publiées du plan de course Garmin Coach, d’aujourd’hui à J+6 inclus dans `Europe/Paris`. La réponse indique les dates demandées, la couverture de chaque semaine dans `weeks` et les entrées par date dans `entries`, avec leur plan. Les jours de repos et de compétition figurent quand Garmin les signale. Les séances déjà associées à une activité réalisée sont écartées.

Garmin peut publier une partie de la semaine. Une date absente reste inconnue, même quand la lecture de la semaine réussit. La couverture d’une semaine vaut `published`, `empty` ou `error`. `partial` conserve les résultats utilisables si une semaine échoue ou contient des entrées invalides. `coach_read_failed` indique l’échec de toutes les lectures de semaine. `no_data` indique une lecture valide sans entrée à afficher. `no_active_plan` signifie que Garmin indique une liste de plans vide.
