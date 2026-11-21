# garmin-mcp

Serveur Spring Boot MCP pour un compte Garmin Connect personnel. Le catalogue expose **36 outils de lecture** pour interroger les activités, la santé, la performance, les suivis personnels et les fichiers FIT du compte. Les noms, les usages et les endpoints officiels figurent dans le [catalogue des outils](_bmad-output/specs/spec-garmin-mcp-tools/catalogue-outils.md).

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

## Créer un entraînement

`garmin_create_workout` enregistre une séance dans la bibliothèque Garmin Connect. Il accepte la course, la marche et le vélo, avec 1 à 50 étapes de durée ou de distance et une zone de fréquence cardiaque facultative. Le premier appel prévisualise les étapes. Passe `confirm: true` après validation pour enregistrer la séance et la relire. Une séance identique portant le même nom est réutilisée. Pour une séance différente, choisis un autre nom.

```json
{
  "name": "Fractionné 400 m",
  "sport": "running",
  "steps": [
    {"kind": "warmup", "durationSeconds": 600},
    {"kind": "interval", "distanceMeters": 400, "heartRateZone": 3},
    {"kind": "cooldown", "durationSeconds": 300}
  ]
}
```

Le 37ᵉ outil est disponible quand `GARMIN_WORKOUT_WRITES_ENABLED=true`. Génère une session en Terminal avec `uv run --script scripts/prototype_garmin_workout.py login --token-dir ~/.garmin-connect-workouts` sur ton poste. En local, installe `garminconnect==0.3.2` dans un environnement Python et indique son exécutable avec `GARMIN_WORKOUT_PYTHON`. Renseigne `GARMIN_CONNECT_TOKEN_DIR` avec le dossier privé de la session. Le serveur compare une activité récente à la Personal API avant la création pour vérifier que les deux accès visent le même compte.

L’image Docker embarque le client Python. Sur Render, ajoute le fichier de la session dédiée aux **Secret Files**, puis règle `GARMIN_CONNECT_SESSION_FILE=/etc/secrets/garmin_tokens.json` et active l’écriture. Le serveur garde une copie privée pour renouveler la session pendant son exécution. Le système de fichiers de Render Free est éphémère. Remplace le fichier secret si Garmin refuse la session après un redémarrage. L’adaptateur utilise l’API interne Garmin Connect, dont les réponses et le mécanisme de connexion peuvent évoluer.
