# garmin-mcp

Serveur Spring Boot MCP en lecture seule pour un compte Garmin Connect personnel. L’outil
`garmin_check_connection` demande au plus une activité et indique uniquement si Garmin répond
et s’il a renvoyé une activité.

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
